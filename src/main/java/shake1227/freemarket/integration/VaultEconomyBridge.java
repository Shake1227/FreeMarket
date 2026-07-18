package shake1227.freemarket.integration;

import com.mojang.logging.LogUtils;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class VaultEconomyBridge {
    public enum Status {
        SUCCESS,
        UNAVAILABLE,
        WRONG_THREAD,
        INVALID_PLAYER,
        INVALID_AMOUNT,
        DECLINED,
        ERROR
    }

    public record BalanceResult(boolean success, Status status, double balance, String provider, String message) {
    }

    public record HasResult(boolean success, Status status, boolean has, String provider, String message) {
    }

    public record TransactionResult(boolean success, Status status, double amount, double balance, String provider, String message) {
    }

    private static final String BUKKIT_CLASS = "org.bukkit.Bukkit";
    private static final String ECONOMY_CLASS = "net.milkbowl.vault.economy.Economy";
    private static final long LOG_INTERVAL_MILLIS = 60_000L;
    private static final long RESOLUTION_RETRY_MILLIS = 5_000L;
    private static final int MAX_FRACTIONAL_DIGITS = 16;
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Object STATE_LOCK = new Object();
    private static final Map<String, Long> NEXT_LOG_TIMES = new HashMap<>();
    private static volatile MinecraftServer server;
    private static volatile ReflectionApi api;
    private static volatile AccessResult cachedFailure;
    private static volatile long retryResolutionAt;
    private static volatile String loggedProvider = "";

    private VaultEconomyBridge() {
    }

    public static void initialize(MinecraftServer minecraftServer) {
        Objects.requireNonNull(minecraftServer, "minecraftServer");
        synchronized (STATE_LOCK) {
            server = minecraftServer;
            api = null;
            cachedFailure = null;
            retryResolutionAt = 0L;
            loggedProvider = "";
            NEXT_LOG_TIMES.clear();
        }
        if (!minecraftServer.isSameThread()) {
            logFailure("initialize-thread", "Vault economy initialization was requested off the server thread", null);
            minecraftServer.execute(() -> initialize(minecraftServer));
            return;
        }
        resolve();
    }

    public static boolean refresh() {
        Status threadStatus = validateThread("refresh");
        if (threadStatus != Status.SUCCESS) {
            return false;
        }
        synchronized (STATE_LOCK) {
            api = null;
            cachedFailure = null;
            retryResolutionAt = 0L;
        }
        return resolve().status() == Status.SUCCESS;
    }

    public static void reset() {
        synchronized (STATE_LOCK) {
            server = null;
            api = null;
            cachedFailure = null;
            retryResolutionAt = 0L;
            loggedProvider = "";
            NEXT_LOG_TIMES.clear();
        }
    }

    public static boolean available() {
        if (validateThread("available") != Status.SUCCESS) {
            return false;
        }
        return resolve().status() == Status.SUCCESS;
    }

    public static BalanceResult balance(UUID playerId) {
        if (playerId == null) {
            return new BalanceResult(false, Status.INVALID_PLAYER, 0.0D, "", "Player UUID is required");
        }
        Status threadStatus = validateThread("balance");
        if (threadStatus != Status.SUCCESS) {
            return new BalanceResult(false, threadStatus, 0.0D, "", statusMessage(threadStatus));
        }
        AccessResult access = resolve();
        if (access.status() != Status.SUCCESS) {
            return new BalanceResult(false, access.status(), 0.0D, "", access.message());
        }
        ReflectionApi resolved = access.api();
        try {
            Object player = resolved.getOfflinePlayer().invoke(null, playerId);
            Object rawBalance = resolved.getBalance().invoke(resolved.provider(), player);
            if (!(rawBalance instanceof Number number) || !Double.isFinite(number.doubleValue())) {
                throw new IllegalStateException("Economy provider returned an invalid balance");
            }
            int fractionalDigits = fractionalDigits(resolved);
            double balance = normalizeValue(number.doubleValue(), fractionalDigits);
            return new BalanceResult(true, Status.SUCCESS, balance, resolved.providerName(), "");
        } catch (ReflectiveOperationException | LinkageError | RuntimeException exception) {
            invalidate(resolved);
            Throwable cause = causeOf(exception);
            logFailure("balance", "Vault economy balance lookup failed", cause);
            String message = messageOf(cause, "Economy balance lookup failed");
            rememberResolution(AccessResult.failure(Status.ERROR, message));
            return new BalanceResult(false, Status.ERROR, 0.0D, resolved.providerName(), message);
        }
    }

    public static HasResult has(UUID playerId, double amount) {
        if (playerId == null) {
            return new HasResult(false, Status.INVALID_PLAYER, false, "", "Player UUID is required");
        }
        if (!validAmount(amount)) {
            return new HasResult(false, Status.INVALID_AMOUNT, false, "", "Amount must be finite and non-negative");
        }
        Status threadStatus = validateThread("has");
        if (threadStatus != Status.SUCCESS) {
            return new HasResult(false, threadStatus, false, "", statusMessage(threadStatus));
        }
        AccessResult access = resolve();
        if (access.status() != Status.SUCCESS) {
            return new HasResult(false, access.status(), false, "", access.message());
        }
        ReflectionApi resolved = access.api();
        try {
            int fractionalDigits = fractionalDigits(resolved);
            double normalizedAmount = normalizeAmount(amount, fractionalDigits);
            if (amount > 0.0D && normalizedAmount == 0.0D) {
                return new HasResult(false, Status.INVALID_AMOUNT, false, resolved.providerName(), "Amount is below the provider's minimum precision");
            }
            Object player = resolved.getOfflinePlayer().invoke(null, playerId);
            Object rawResult = resolved.has().invoke(resolved.provider(), player, normalizedAmount);
            if (!(rawResult instanceof Boolean result)) {
                throw new IllegalStateException("Economy provider returned an invalid funds check");
            }
            return new HasResult(true, Status.SUCCESS, result, resolved.providerName(), "");
        } catch (ReflectiveOperationException | LinkageError | RuntimeException exception) {
            invalidate(resolved);
            Throwable cause = causeOf(exception);
            logFailure("has", "Vault economy funds check failed", cause);
            String message = messageOf(cause, "Economy funds check failed");
            rememberResolution(AccessResult.failure(Status.ERROR, message));
            return new HasResult(false, Status.ERROR, false, resolved.providerName(), message);
        }
    }

    public static TransactionResult withdraw(UUID playerId, double amount) {
        return transact(playerId, amount, false);
    }

    public static TransactionResult deposit(UUID playerId, double amount) {
        return transact(playerId, amount, true);
    }

    private static TransactionResult transact(UUID playerId, double amount, boolean deposit) {
        if (playerId == null) {
            return new TransactionResult(false, Status.INVALID_PLAYER, amount, 0.0D, "", "Player UUID is required");
        }
        if (!validAmount(amount)) {
            return new TransactionResult(false, Status.INVALID_AMOUNT, amount, 0.0D, "", "Amount must be finite and non-negative");
        }
        String operation = deposit ? "deposit" : "withdraw";
        Status threadStatus = validateThread(operation);
        if (threadStatus != Status.SUCCESS) {
            return new TransactionResult(false, threadStatus, amount, 0.0D, "", statusMessage(threadStatus));
        }
        AccessResult access = resolve();
        if (access.status() != Status.SUCCESS) {
            return new TransactionResult(false, access.status(), amount, 0.0D, "", access.message());
        }
        ReflectionApi resolved = access.api();
        Method method = deposit ? resolved.deposit() : resolved.withdraw();
        double requestedAmount = amount;
        try {
            int fractionalDigits = fractionalDigits(resolved);
            requestedAmount = normalizeAmount(amount, fractionalDigits);
            if (amount > 0.0D && requestedAmount == 0.0D) {
                return new TransactionResult(false, Status.INVALID_AMOUNT, requestedAmount, 0.0D, resolved.providerName(), "Amount is below the provider's minimum precision");
            }
            Object player = resolved.getOfflinePlayer().invoke(null, playerId);
            Object response = method.invoke(resolved.provider(), player, requestedAmount);
            if (response == null) {
                throw new IllegalStateException("Economy provider returned no transaction response");
            }
            Object rawSuccess = resolved.transactionSuccess().invoke(response);
            if (!(rawSuccess instanceof Boolean success)) {
                throw new IllegalStateException("Economy provider returned an invalid transaction status");
            }
            double rawResponseAmount = requiredFiniteField(resolved.responseAmount(), response, "transaction amount");
            if (rawResponseAmount < 0.0D) {
                throw new IllegalStateException("Economy provider returned a negative transaction amount");
            }
            double responseAmount = normalizeAmount(rawResponseAmount, fractionalDigits);
            double responseBalance = normalizeValue(requiredFiniteField(resolved.responseBalance(), response, "transaction balance"), fractionalDigits);
            String responseMessage = stringField(resolved.responseError(), response);
            if (success) {
                if (!amountsEquivalent(requestedAmount, rawResponseAmount, fractionalDigits)) {
                    return inconsistentTransaction(resolved, operation, responseAmount, responseBalance, "Economy provider reported success after processing a different amount");
                }
                return new TransactionResult(true, Status.SUCCESS, requestedAmount, responseBalance, resolved.providerName(), "");
            }
            if (!amountsEquivalent(0.0D, rawResponseAmount, fractionalDigits)) {
                return inconsistentTransaction(resolved, operation, responseAmount, responseBalance, "Economy provider declined after partially processing the transaction");
            }
            String message = responseMessage.isBlank() ? "Economy provider declined the transaction" : responseMessage;
            return new TransactionResult(false, Status.DECLINED, responseAmount, responseBalance, resolved.providerName(), message);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException exception) {
            invalidate(resolved);
            Throwable cause = causeOf(exception);
            logFailure(operation, "Vault economy " + operation + " failed", cause);
            String fallback = "Economy provider failed; transaction outcome is unknown";
            String message = messageOf(cause, fallback);
            rememberResolution(AccessResult.failure(Status.ERROR, message));
            return new TransactionResult(false, Status.ERROR, requestedAmount, 0.0D, resolved.providerName(), message);
        }
    }

    private static AccessResult resolve() {
        AccessResult failure = cachedFailure;
        if (failure != null && System.currentTimeMillis() < retryResolutionAt) {
            return failure;
        }
        ReflectionApi cached = api;
        if (cached != null) {
            AccessResult refreshed = refreshCached(cached);
            if (refreshed.status() == Status.SUCCESS || refreshed.status() == Status.WRONG_THREAD) {
                return refreshed;
            }
        }
        try {
            return rememberResolution(discover());
        } catch (ClassNotFoundException exception) {
            logUnavailable("bukkit-missing", "Vault economy integration is unavailable because Bukkit is not present");
            return rememberResolution(AccessResult.failure(Status.UNAVAILABLE, "Bukkit is not available"));
        } catch (ReflectiveOperationException | LinkageError | RuntimeException exception) {
            Throwable cause = causeOf(exception);
            logFailure("resolution", "Vault economy integration resolution failed", cause);
            synchronized (STATE_LOCK) {
                api = null;
            }
            return rememberResolution(AccessResult.failure(Status.ERROR, messageOf(cause, "Vault economy integration failed")));
        }
    }

    private static AccessResult refreshCached(ReflectionApi cached) {
        try {
            if (!isBukkitPrimaryThread(cached)) {
                logFailure("bukkit-thread", "Vault economy access was attempted off the Bukkit primary thread", null);
                return AccessResult.failure(Status.WRONG_THREAD, "Economy access must run on the server thread");
            }
            Object registration = cached.getRegistration().invoke(cached.services(), cached.economyClass());
            if (registration == null) {
                invalidate(cached);
                return AccessResult.failure(Status.UNAVAILABLE, "No Vault economy provider is registered");
            }
            Object provider = cached.getProvider().invoke(registration);
            if (provider == null || !cached.economyClass().isInstance(provider)) {
                invalidate(cached);
                return AccessResult.failure(Status.UNAVAILABLE, "No Vault economy provider is registered");
            }
            ReflectionApi refreshed = provider == cached.provider() ? cached : cached.withProvider(provider, providerName(cached.getName(), provider));
            if (!providerEnabled(refreshed.isEnabled(), provider)) {
                api = refreshed;
                return AccessResult.failure(Status.UNAVAILABLE, "The Vault economy provider is disabled");
            }
            api = refreshed;
            cachedFailure = null;
            retryResolutionAt = 0L;
            logProvider(refreshed.providerName());
            return AccessResult.success(refreshed);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException exception) {
            invalidate(cached);
            Throwable cause = causeOf(exception);
            logFailure("cached-resolution", "Cached Vault economy provider validation failed", cause);
            return AccessResult.failure(Status.ERROR, messageOf(cause, "Vault economy provider validation failed"));
        }
    }

    private static AccessResult discover() throws ReflectiveOperationException {
        Class<?> bukkitClass = loadBukkitClass();
        Method isPrimaryThread = bukkitClass.getMethod("isPrimaryThread");
        if (!Boolean.TRUE.equals(isPrimaryThread.invoke(null))) {
            logFailure("bukkit-thread", "Vault economy access was attempted off the Bukkit primary thread", null);
            return AccessResult.failure(Status.WRONG_THREAD, "Economy access must run on the server thread");
        }
        Method getServicesManager = bukkitClass.getMethod("getServicesManager");
        Object services = getServicesManager.invoke(null);
        if (services == null) {
            logUnavailable("services-missing", "Vault economy integration is unavailable because Bukkit services are not initialized");
            return AccessResult.failure(Status.UNAVAILABLE, "Bukkit services are not initialized");
        }
        Class<?> servicesClass = getServicesManager.getReturnType();
        Method getKnownServices = servicesClass.getMethod("getKnownServices");
        Method getRegistration = servicesClass.getMethod("getRegistration", Class.class);
        Object rawKnownServices = getKnownServices.invoke(services);
        if (!(rawKnownServices instanceof Collection<?> knownServices)) {
            throw new IllegalStateException("Bukkit returned an invalid services collection");
        }
        Method getOfflinePlayer = bukkitClass.getMethod("getOfflinePlayer", UUID.class);
        Class<?> offlinePlayerClass = getOfflinePlayer.getReturnType();
        boolean economyServiceSeen = false;
        for (Object candidate : knownServices) {
            if (!(candidate instanceof Class<?> economyClass) || !ECONOMY_CLASS.equals(economyClass.getName())) {
                continue;
            }
            economyServiceSeen = true;
            Object registration = getRegistration.invoke(services, economyClass);
            if (registration == null) {
                continue;
            }
            Method getProvider = getRegistration.getReturnType().getMethod("getProvider");
            Object provider = getProvider.invoke(registration);
            if (provider == null || !economyClass.isInstance(provider)) {
                continue;
            }
            Method isEnabled = economyClass.getMethod("isEnabled");
            Method getName = economyClass.getMethod("getName");
            Method fractionalDigits = economyClass.getMethod("fractionalDigits");
            Method getBalance = economyClass.getMethod("getBalance", offlinePlayerClass);
            Method has = economyClass.getMethod("has", offlinePlayerClass, double.class);
            Method withdraw = economyClass.getMethod("withdrawPlayer", offlinePlayerClass, double.class);
            Method deposit = economyClass.getMethod("depositPlayer", offlinePlayerClass, double.class);
            Class<?> responseClass = withdraw.getReturnType();
            Method transactionSuccess = responseClass.getMethod("transactionSuccess");
            Field responseAmount = responseClass.getField("amount");
            Field responseBalance = responseClass.getField("balance");
            Field responseError = responseClass.getField("errorMessage");
            ReflectionApi resolved = new ReflectionApi(
                economyClass,
                services,
                getRegistration,
                getProvider,
                provider,
                providerName(getName, provider),
                isPrimaryThread,
                getOfflinePlayer,
                isEnabled,
                getName,
                fractionalDigits,
                getBalance,
                has,
                withdraw,
                deposit,
                transactionSuccess,
                responseAmount,
                responseBalance,
                responseError
            );
            if (!providerEnabled(isEnabled, provider)) {
                api = resolved;
                logUnavailable("provider-disabled", "Vault economy provider is registered but disabled");
                return AccessResult.failure(Status.UNAVAILABLE, "The Vault economy provider is disabled");
            }
            api = resolved;
            cachedFailure = null;
            retryResolutionAt = 0L;
            logProvider(resolved.providerName());
            return AccessResult.success(resolved);
        }
        if (economyServiceSeen) {
            logUnavailable("provider-missing", "Vault Economy is present but no provider is registered");
            return AccessResult.failure(Status.UNAVAILABLE, "No Vault economy provider is registered");
        }
        logUnavailable("economy-missing", "Vault Economy service is not registered");
        return AccessResult.failure(Status.UNAVAILABLE, "Vault Economy is not available");
    }

    private static Class<?> loadBukkitClass() throws ClassNotFoundException {
        ClassLoader ownLoader = VaultEconomyBridge.class.getClassLoader();
        try {
            return Class.forName(BUKKIT_CLASS, false, ownLoader);
        } catch (ClassNotFoundException first) {
            ClassLoader serverLoader = server == null ? null : server.getClass().getClassLoader();
            if (serverLoader != null && serverLoader != ownLoader) {
                try {
                    return Class.forName(BUKKIT_CLASS, false, serverLoader);
                } catch (ClassNotFoundException ignored) {
                }
            }
            ClassLoader contextLoader = Thread.currentThread().getContextClassLoader();
            if (contextLoader != null && contextLoader != ownLoader && contextLoader != serverLoader) {
                return Class.forName(BUKKIT_CLASS, false, contextLoader);
            }
            throw first;
        }
    }

    private static Status validateThread(String operation) {
        MinecraftServer current = server;
        if (current == null) {
            logUnavailable("not-initialized", "Vault economy integration has not been initialized");
            return Status.UNAVAILABLE;
        }
        if (!current.isSameThread()) {
            logFailure("wrong-thread-" + operation, "Vault economy " + operation + " was attempted off the server thread", null);
            return Status.WRONG_THREAD;
        }
        return Status.SUCCESS;
    }

    private static boolean isBukkitPrimaryThread(ReflectionApi resolved) throws ReflectiveOperationException {
        return Boolean.TRUE.equals(resolved.isPrimaryThread().invoke(null));
    }

    private static boolean providerEnabled(Method isEnabled, Object provider) throws ReflectiveOperationException {
        return Boolean.TRUE.equals(isEnabled.invoke(provider));
    }

    private static String providerName(Method getName, Object provider) throws ReflectiveOperationException {
        Object value = getName.invoke(provider);
        return value == null ? provider.getClass().getName() : value.toString();
    }

    private static boolean validAmount(double amount) {
        return Double.isFinite(amount) && amount >= 0.0D;
    }

    private static int fractionalDigits(ReflectionApi resolved) throws ReflectiveOperationException {
        Object rawDigits = resolved.fractionalDigits().invoke(resolved.provider());
        if (!(rawDigits instanceof Integer digits) || digits < -1) {
            throw new IllegalStateException("Economy provider returned an invalid fractional digit count");
        }
        return Math.min(digits, MAX_FRACTIONAL_DIGITS);
    }

    private static double normalizeAmount(double amount, int fractionalDigits) {
        if (!validAmount(amount)) {
            throw new IllegalArgumentException("Amount must be finite and non-negative");
        }
        return normalizeValue(amount, fractionalDigits);
    }

    private static double normalizeValue(double value, int fractionalDigits) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("Value must be finite");
        }
        double normalized = fractionalDigits < 0
            ? value
            : BigDecimal.valueOf(value).setScale(fractionalDigits, RoundingMode.HALF_UP).doubleValue();
        if (!Double.isFinite(normalized)) {
            throw new IllegalArgumentException("Normalized value must be finite");
        }
        return normalized == 0.0D ? 0.0D : normalized;
    }

    private static boolean amountsEquivalent(double expected, double actual, int fractionalDigits) {
        if (!Double.isFinite(expected) || !Double.isFinite(actual)) {
            return false;
        }
        double normalizedExpected = normalizeValue(expected, fractionalDigits);
        double normalizedActual = normalizeValue(actual, fractionalDigits);
        double scale = Math.max(Math.max(Math.abs(normalizedExpected), Math.abs(normalizedActual)), 1.0D);
        double tolerance = Math.ulp(scale) * 2.0D;
        return Math.abs(normalizedExpected - normalizedActual) <= tolerance;
    }

    private static double requiredFiniteField(Field field, Object target, String label) throws IllegalAccessException {
        Object value = field.get(target);
        if (value instanceof Number number && Double.isFinite(number.doubleValue())) {
            return number.doubleValue();
        }
        throw new IllegalStateException("Economy provider returned an invalid " + label);
    }

    private static String stringField(Field field, Object target) throws IllegalAccessException {
        Object value = field.get(target);
        return value == null ? "" : value.toString();
    }

    private static TransactionResult inconsistentTransaction(ReflectionApi resolved, String operation, double amount, double balance, String message) {
        invalidate(resolved);
        logFailure(operation + "-amount", message, null);
        rememberResolution(AccessResult.failure(Status.ERROR, message));
        return new TransactionResult(false, Status.ERROR, amount, balance, resolved.providerName(), message);
    }

    private static void invalidate(ReflectionApi expected) {
        synchronized (STATE_LOCK) {
            if (api == expected) {
                api = null;
            }
        }
    }

    private static AccessResult rememberResolution(AccessResult result) {
        synchronized (STATE_LOCK) {
            if (result.status() == Status.SUCCESS) {
                cachedFailure = null;
                retryResolutionAt = 0L;
            } else if (result.status() != Status.WRONG_THREAD) {
                cachedFailure = result;
                retryResolutionAt = System.currentTimeMillis() + RESOLUTION_RETRY_MILLIS;
            }
        }
        return result;
    }

    private static Throwable causeOf(Throwable throwable) {
        if (throwable instanceof InvocationTargetException invocation && invocation.getCause() != null) {
            return invocation.getCause();
        }
        return throwable;
    }

    private static String messageOf(Throwable throwable, String fallback) {
        String message = throwable == null ? null : throwable.getMessage();
        return message == null || message.isBlank() ? fallback : message;
    }

    private static String statusMessage(Status status) {
        return switch (status) {
            case UNAVAILABLE -> "Vault Economy is not available";
            case WRONG_THREAD -> "Economy access must run on the server thread";
            default -> "Economy operation failed";
        };
    }

    private static void logProvider(String provider) {
        if (Objects.equals(loggedProvider, provider)) {
            return;
        }
        synchronized (STATE_LOCK) {
            if (Objects.equals(loggedProvider, provider)) {
                return;
            }
            loggedProvider = provider;
        }
        LOGGER.info("Vault economy integration enabled with provider {}", provider);
    }

    private static void logUnavailable(String key, String message) {
        if (acquireLogSlot(key)) {
            LOGGER.info(message);
        }
    }

    private static void logFailure(String key, String message, Throwable throwable) {
        if (!acquireLogSlot(key)) {
            return;
        }
        if (throwable == null) {
            LOGGER.warn(message);
        } else {
            LOGGER.warn(message, throwable);
        }
    }

    private static boolean acquireLogSlot(String key) {
        long now = System.currentTimeMillis();
        synchronized (STATE_LOCK) {
            long next = NEXT_LOG_TIMES.getOrDefault(key, 0L);
            if (now < next) {
                return false;
            }
            NEXT_LOG_TIMES.put(key, now + LOG_INTERVAL_MILLIS);
            return true;
        }
    }

    private record AccessResult(ReflectionApi api, Status status, String message) {
        private static AccessResult success(ReflectionApi api) {
            return new AccessResult(api, Status.SUCCESS, "");
        }

        private static AccessResult failure(Status status, String message) {
            return new AccessResult(null, status, message);
        }
    }

    private record ReflectionApi(
        Class<?> economyClass,
        Object services,
        Method getRegistration,
        Method getProvider,
        Object provider,
        String providerName,
        Method isPrimaryThread,
        Method getOfflinePlayer,
        Method isEnabled,
        Method getName,
        Method fractionalDigits,
        Method getBalance,
        Method has,
        Method withdraw,
        Method deposit,
        Method transactionSuccess,
        Field responseAmount,
        Field responseBalance,
        Field responseError
    ) {
        private ReflectionApi withProvider(Object provider, String providerName) {
            return new ReflectionApi(
                economyClass,
                services,
                getRegistration,
                getProvider,
                provider,
                providerName,
                isPrimaryThread,
                getOfflinePlayer,
                isEnabled,
                getName,
                fractionalDigits,
                getBalance,
                has,
                withdraw,
                deposit,
                transactionSuccess,
                responseAmount,
                responseBalance,
                responseError
            );
        }
    }
}

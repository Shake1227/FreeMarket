package shake1227.freemarket.server;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.level.storage.PlayerDataStorage;
import net.minecraftforge.event.ForgeEventFactory;
import shake1227.freemarket.FreeMarket;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

public final class PlayerDataPersistence {
    private static volatile Field storageField;

    private PlayerDataPersistence() {
    }

    public static void save(ServerPlayer player) {
        try {
            saveOrThrow(player);
        } catch (RuntimeException exception) {
            FreeMarket.LOGGER.warn("Direct player data save failed; using server save fallback", exception);
            player.server.saveEverything(true, false, false);
        }
    }

    public static void saveOrThrow(ServerPlayer player) {
        PlayerDataStorage storage = storage(player);
        Path directory = storage.getPlayerDataFolder().toPath();
        Path target = directory.resolve(player.getStringUUID() + ".dat");
        Path backup = directory.resolve(player.getStringUUID() + ".dat_old");
        Path temporary = directory.resolve(player.getStringUUID() + ".freemarket.tmp");
        try {
            Files.createDirectories(directory);
            CompoundTag data = player.saveWithoutId(new CompoundTag());
            NbtIo.writeCompressed(data, temporary.toFile());
            force(temporary);
            if (Files.isRegularFile(target)) {
                Files.copy(target, backup, StandardCopyOption.REPLACE_EXISTING);
                force(backup);
            }
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
            force(target);
            ForgeEventFactory.firePlayerSavingEvent(player, directory.toFile(), player.getStringUUID());
        } catch (IOException | RuntimeException exception) {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException ignored) {
            }
            throw new IllegalStateException("player data save failed", exception);
        }
    }

    private static PlayerDataStorage storage(ServerPlayer player) {
        try {
            Field field = storageField;
            if (field == null) {
                field = resolveField();
                storageField = field;
            }
            Object value = field.get(player.server.getPlayerList());
            if (value instanceof PlayerDataStorage storage) {
                return storage;
            }
            throw new IllegalStateException("player data storage unavailable");
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("player data storage unavailable", exception);
        }
    }

    private static void force(Path path) throws IOException {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE)) {
            channel.force(true);
        }
    }

    private static Field resolveField() throws NoSuchFieldException {
        for (Field field : PlayerList.class.getDeclaredFields()) {
            if (PlayerDataStorage.class.isAssignableFrom(field.getType())) {
                field.setAccessible(true);
                return field;
            }
        }
        throw new NoSuchFieldException(PlayerDataStorage.class.getName());
    }
}

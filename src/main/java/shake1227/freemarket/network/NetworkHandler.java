package shake1227.freemarket.network;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;
import io.netty.handler.codec.DecoderException;
import shake1227.freemarket.FreeMarket;
import shake1227.freemarket.client.ClientMarketController;
import shake1227.freemarket.server.MarketServerController;

import java.util.function.Supplier;

public final class NetworkHandler {
    private static final String PROTOCOL = "1";
    private static final int MAX_ACTION_LENGTH = 48;
    private static final int MAX_SERVERBOUND_DATA_BYTES = 32 * 1024;
    private static final SimpleChannel CHANNEL = NetworkRegistry.ChannelBuilder
        .named(new ResourceLocation(FreeMarket.MODID, "market"))
        .networkProtocolVersion(() -> PROTOCOL)
        .clientAcceptedVersions(PROTOCOL::equals)
        .serverAcceptedVersions(PROTOCOL::equals)
        .simpleChannel();
    private static boolean registered;

    private NetworkHandler() {
    }

    public static synchronized void register() {
        if (registered) {
            return;
        }
        registered = true;
        CHANNEL.messageBuilder(ServerboundPayload.class, 0, NetworkDirection.PLAY_TO_SERVER)
            .encoder(ServerboundPayload::encode)
            .decoder(ServerboundPayload::decode)
            .consumerMainThread(ServerboundPayload::handle)
            .add();
        CHANNEL.messageBuilder(ClientboundPayload.class, 1, NetworkDirection.PLAY_TO_CLIENT)
            .encoder(ClientboundPayload::encode)
            .decoder(ClientboundPayload::decode)
            .consumerMainThread(ClientboundPayload::handle)
            .add();
    }

    public static void sendToServer(String action, CompoundTag data) {
        CHANNEL.sendToServer(new ServerboundPayload(normalizeAction(action), safeTag(data)));
    }

    public static void sendToPlayer(ServerPlayer player, String action, CompoundTag data) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new ClientboundPayload(normalizeAction(action), safeTag(data)));
    }

    public static void openMarket(ServerPlayer player) {
        MarketServerController.open(player);
    }

    private static String normalizeAction(String action) {
        if (action == null) {
            return "";
        }
        String normalized = action.trim().toLowerCase(java.util.Locale.ROOT);
        return normalized.length() > MAX_ACTION_LENGTH ? normalized.substring(0, MAX_ACTION_LENGTH) : normalized;
    }

    private static CompoundTag safeTag(CompoundTag tag) {
        return tag == null ? new CompoundTag() : tag.copy();
    }

    public record ServerboundPayload(String action, CompoundTag data) {
        private static void encode(ServerboundPayload payload, FriendlyByteBuf buffer) {
            buffer.writeUtf(payload.action, MAX_ACTION_LENGTH);
            buffer.writeNbt(payload.data);
        }

        private static ServerboundPayload decode(FriendlyByteBuf buffer) {
            String action = buffer.readUtf(MAX_ACTION_LENGTH);
            if (buffer.readableBytes() > MAX_SERVERBOUND_DATA_BYTES) {
                throw new DecoderException("FreeMarket payload exceeds " + MAX_SERVERBOUND_DATA_BYTES + " bytes");
            }
            CompoundTag tag = buffer.readNbt();
            return new ServerboundPayload(action, tag == null ? new CompoundTag() : tag);
        }

        private static void handle(ServerboundPayload payload, Supplier<NetworkEvent.Context> contextSupplier) {
            ServerPlayer sender = contextSupplier.get().getSender();
            if (sender != null) {
                MarketServerController.handle(sender, payload.action, payload.data);
            }
            contextSupplier.get().setPacketHandled(true);
        }
    }

    public record ClientboundPayload(String action, CompoundTag data) {
        private static void encode(ClientboundPayload payload, FriendlyByteBuf buffer) {
            buffer.writeUtf(payload.action, MAX_ACTION_LENGTH);
            buffer.writeNbt(payload.data);
        }

        private static ClientboundPayload decode(FriendlyByteBuf buffer) {
            String action = buffer.readUtf(MAX_ACTION_LENGTH);
            CompoundTag tag = buffer.readNbt();
            return new ClientboundPayload(action, tag == null ? new CompoundTag() : tag);
        }

        private static void handle(ClientboundPayload payload, Supplier<NetworkEvent.Context> contextSupplier) {
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ClientMarketController.handle(payload.action, payload.data));
            contextSupplier.get().setPacketHandled(true);
        }
    }
}

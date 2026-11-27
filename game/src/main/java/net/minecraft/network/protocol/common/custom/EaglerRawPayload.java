package net.minecraft.network.protocol.common.custom;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;

/**
 * EaglerXR: an opaque plugin-channel payload kept as raw bytes, for channels whose
 * format is defined outside vanilla (vivecraft:data, minecraft:register). Vanilla
 * turns every unknown channel into a {@link DiscardedPayload}, which drops the bytes
 * on receive and writes none on send; these channels are routed here instead.
 */
public record EaglerRawPayload(Identifier id, byte[] data) implements CustomPacketPayload {

	/** Vivecraft's plugin channel (Vivecraft CommonNetworkHelper.CHANNEL). */
	public static final Identifier VIVECRAFT = Identifier.fromNamespaceAndPath("vivecraft", "data");
	/** Plugin channel registration (NUL-separated channel ids), used by Bukkit/Spigot, Fabric and proxies. */
	public static final Identifier REGISTER = Identifier.fromNamespaceAndPath("minecraft", "register");

	public static boolean isRaw(final Identifier id) {
		return VIVECRAFT.equals(id) || REGISTER.equals(id);
	}

	/** Fallback codec for unknown channels: raw bytes for {@link #isRaw} ids, otherwise vanilla's discard. */
	public static <B extends FriendlyByteBuf> StreamCodec<B, ? extends CustomPacketPayload> fallback(final Identifier id, final int maxPayloadSize) {
		if (!isRaw(id)) {
			return DiscardedPayload.codec(id, maxPayloadSize);
		}
		return CustomPacketPayload.<B, EaglerRawPayload>codec((payload, buf) -> buf.writeBytes(payload.data()), buf -> {
			int length = buf.readableBytes();
			if (length < 0 || length > maxPayloadSize) {
				throw new IllegalArgumentException("Payload may not be larger than " + maxPayloadSize + " bytes");
			}
			byte[] bytes = new byte[length];
			buf.readBytes(bytes);
			return new EaglerRawPayload(id, bytes);
		});
	}

	@Override
	public CustomPacketPayload.Type<EaglerRawPayload> type() {
		return new CustomPacketPayload.Type<>(this.id);
	}
}

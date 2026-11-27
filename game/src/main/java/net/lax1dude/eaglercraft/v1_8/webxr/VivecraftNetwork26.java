package net.lax1dude.eaglercraft.v1_8.webxr;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.lax1dude.eaglercraft.v1_8.internal.PlatformWebXR;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.EaglerRawPayload;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;

/**
 * EaglerXR: best-effort client side of the Vivecraft server protocol (plugin channel
 * {@code vivecraft:data}), so a server running Vivecraft's server side (the Vivecraft mod
 * on a Fabric/Forge/NeoForge server, or a Vivecraft Spigot/Paper plugin) sees this player
 * as a VR player and relays the head / hand poses to Vivecraft clients.
 *
 * <p>Off by default (VR Settings "Vivecraft server support", {@code eaglerxr.vivecraftNetwork}).
 * The wire format is reimplemented from VivecraftMod (branch Multiloader-26.3,
 * {@code org.vivecraft.common.network}); no Vivecraft code is copied. Every payload is
 * {@code [byte PayloadIdentifier ordinal][body]}. Handshake, as Vivecraft's client does it
 * on login / dimension change:
 * <ol>
 * <li>C2S {@code minecraft:register} "vivecraft:data" (needed for Bukkit-style plugin channels)
 * and VERSION (0): UTF-8 {@code "<version> VR|NONVR\n<maxProto>\n<minProto>"}.
 * We claim network protocol 0 (NEW_NETWORKING) only: no full-body tracking, dual wielding,
 * head aim or roomscale-attack packets.</li>
 * <li>S2C VERSION (0, server has Vivecraft), REQUESTDATA (1, start sending poses),
 * NETWORK_VERSION (15, protocol to use; absent from legacy servers).</li>
 * <li>Each tick while presenting: WORLDSCALE (5) / HEIGHT (12) floats once, then
 * VR_PLAYER_STATE (18) {@code seated, hmd, leftHanded, main, leftHanded, off}, or for legacy
 * servers HEADDATA (2) / CONTROLLER0DATA (3) / CONTROLLER1DATA (4). A pose is position
 * (3 floats, relative to the player's feet, world axes) + orientation quaternion as
 * w, x, y, z (whose local -Z is the pointing direction). Floats are big-endian.</li>
 * <li>S2C UBERPACKET (8): {@code UUID, VrPlayerState, worldScale, heightScale} for other VR
 * players - parsed and kept in {@link #otherPlayers()}; not rendered yet.</li>
 * </ol>
 * Visor (VisorModStudio) does not speak this protocol: it uses its own {@code visor:channel}
 * and needs the Visor mod on the server, so it is not implemented here.
 */
public final class VivecraftNetwork26 {

	public static final String VERSION_STRING = "Vivecraft-26.2-eaglerxr-0.1.0";

	private static final int VERSION = 0;
	private static final int REQUESTDATA = 1;
	private static final int HEADDATA = 2;
	private static final int CONTROLLER0DATA = 3;
	private static final int CONTROLLER1DATA = 4;
	private static final int WORLDSCALE = 5;
	private static final int UBERPACKET = 8;
	private static final int HEIGHT = 12;
	private static final int NETWORK_VERSION = 15;
	private static final int IS_VR_ACTIVE = 17;
	private static final int VR_PLAYER_STATE = 18;

	private static boolean enabled;
	private static boolean serverHasVivecraft;
	private static boolean serverWantsData;
	private static boolean serverRegisteredChannel;
	/** Network protocol the server chose (NETWORK_VERSION), or -1 = none received (legacy). */
	private static int networkVersion = -1;
	private static int ticksSinceRequest;
	private static boolean sentScale;
	private static boolean lastVR;
	private static final Map<UUID, float[]> others = new HashMap<>();

	private VivecraftNetwork26() {
	}

	/** Login / respawn into a new level: reset and, when enabled, announce ourselves. */
	public static void onJoin(final ClientPacketListener connection) {
		serverHasVivecraft = false;
		serverWantsData = false;
		networkVersion = -1;
		ticksSinceRequest = 0;
		sentScale = false;
		others.clear();
		enabled = PlatformWebXR.getConfig(VRSettingsScreen.VIVECRAFT_NET, 0.0F) != 0.0F;
		if (!enabled || connection == null) {
			return;
		}
		lastVR = PlatformWebXR.isPresenting();
		send(connection, EaglerRawPayload.REGISTER, EaglerRawPayload.VIVECRAFT.toString().getBytes(StandardCharsets.UTF_8));
		String version = VERSION_STRING + " " + (lastVR ? "VR" : "NONVR") + "\n0\n0";
		byte[] text = version.getBytes(StandardCharsets.UTF_8);
		byte[] body = new byte[text.length + 1];
		body[0] = VERSION;
		System.arraycopy(text, 0, body, 1, text.length);
		send(connection, EaglerRawPayload.VIVECRAFT, body);
	}

	/** Returns true when the payload was ours (consumed). Runs on the client thread. */
	public static boolean handle(final EaglerRawPayload payload) {
		byte[] d = payload.data();
		if (EaglerRawPayload.REGISTER.equals(payload.id())) {
			String channels = new String(d, StandardCharsets.UTF_8);
			for (String channel : channels.split("\u0000")) {
				if (EaglerRawPayload.VIVECRAFT.toString().equals(channel.trim())) {
					serverRegisteredChannel = true;
				}
			}
			return true;
		}
		if (!EaglerRawPayload.VIVECRAFT.equals(payload.id())) {
			return false;
		}
		if (!enabled || d.length == 0) {
			return true;
		}
		try {
			switch (d[0]) {
				case VERSION -> serverHasVivecraft = true;
				case REQUESTDATA -> {
					serverWantsData = true;
					ticksSinceRequest = 0;
				}
				case NETWORK_VERSION -> networkVersion = d.length > 1 ? d[1] & 0xFF : 0;
				case UBERPACKET -> readUber(d);
				case IS_VR_ACTIVE -> {
					// [bool vr][UUID]: a player left VR
					if (d.length >= 18 && d[1] == 0) {
						others.remove(new UUID(readLong(d, 2), readLong(d, 10)));
					}
				}
				default -> {
				}
			}
		} catch (RuntimeException e) {
			// malformed / unexpected packet: ignore, this is best effort
		}
		return true;
	}

	/** Once per client tick: sends this player's poses while presenting and the server asked for data. */
	public static void tick(final Minecraft minecraft) {
		ClientPacketListener connection = minecraft.getConnection();
		LocalPlayer player = minecraft.player;
		if (!enabled || connection == null || player == null || !serverHasVivecraft) {
			return;
		}
		boolean vr = PlatformWebXR.isPresenting();
		if (vr != lastVR) {
			lastVR = vr;
			send(connection, EaglerRawPayload.VIVECRAFT, new byte[] { IS_VR_ACTIVE, (byte) (vr ? 1 : 0) });
		}
		if (!serverWantsData || !vr || minecraft.getCameraEntity() != player) {
			return;
		}
		// NETWORK_VERSION follows REQUESTDATA in the same burst; give it a moment before assuming legacy.
		if (networkVersion < 0 && ticksSinceRequest++ < 10) {
			return;
		}
		float[][] poses = XRControllerInput26.networkPoses(player);
		if (poses == null) {
			return;
		}
		boolean leftHanded = PlatformWebXR.getConfig("leftHanded", 0.0F) != 0.0F;
		if (!sentScale) {
			sentScale = true;
			send(connection, EaglerRawPayload.VIVECRAFT, floatPacket(WORLDSCALE, 1.0F));
			send(connection, EaglerRawPayload.VIVECRAFT, floatPacket(HEIGHT, 1.0F));
		}
		try {
			if (networkVersion >= 0) {
				ByteArrayOutputStream bytes = new ByteArrayOutputStream(96);
				DataOutputStream out = new DataOutputStream(bytes);
				out.writeByte(VR_PLAYER_STATE);
				out.writeBoolean(false); // seated
				writePose(out, poses[0]);
				out.writeBoolean(leftHanded);
				writePose(out, poses[1]);
				out.writeBoolean(leftHanded);
				writePose(out, poses[2]);
				send(connection, EaglerRawPayload.VIVECRAFT, bytes.toByteArray());
			} else {
				send(connection, EaglerRawPayload.VIVECRAFT, legacy(CONTROLLER0DATA, leftHanded, poses[1]));
				send(connection, EaglerRawPayload.VIVECRAFT, legacy(CONTROLLER1DATA, leftHanded, poses[2]));
				send(connection, EaglerRawPayload.VIVECRAFT, legacy(HEADDATA, false, poses[0]));
			}
		} catch (IOException e) {
			// ByteArrayOutputStream does not throw
		}
	}

	/** Other VR players' last state from UBERPACKET: [hmd, main, off] poses (x, y, z, qx, qy, qz, qw) relative to their feet. */
	public static Map<UUID, float[]> otherPlayers() {
		return others;
	}

	public static boolean isServerVivecraft() {
		return serverHasVivecraft;
	}

	public static boolean didServerRegisterChannel() {
		return serverRegisteredChannel;
	}

	// ------------------------------------------------------------------ encoding

	private static void send(final ClientPacketListener connection, final net.minecraft.resources.Identifier channel, final byte[] data) {
		connection.send(new ServerboundCustomPayloadPacket(new EaglerRawPayload(channel, data)));
	}

	private static byte[] floatPacket(final int id, final float value) {
		int bits = Float.floatToIntBits(value);
		return new byte[] { (byte) id, (byte) (bits >>> 24), (byte) (bits >>> 16), (byte) (bits >>> 8), (byte) bits };
	}

	private static byte[] legacy(final int id, final boolean flag, final float[] pose) throws IOException {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream(40);
		DataOutputStream out = new DataOutputStream(bytes);
		out.writeByte(id);
		out.writeBoolean(flag);
		writePose(out, pose);
		return bytes.toByteArray();
	}

	/** pose = x, y, z, qx, qy, qz, qw; Vivecraft writes the quaternion as w, x, y, z. */
	private static void writePose(final DataOutputStream out, final float[] pose) throws IOException {
		out.writeFloat(pose[0]);
		out.writeFloat(pose[1]);
		out.writeFloat(pose[2]);
		out.writeFloat(pose[6]);
		out.writeFloat(pose[3]);
		out.writeFloat(pose[4]);
		out.writeFloat(pose[5]);
	}

	private static void readUber(final byte[] d) {
		// [8][UUID 16][seated 1][hmd 28][lh 1][main 28][lh 1][off 28](fbt...)[worldScale 4][height 4]
		if (d.length < 1 + 16 + 3 + 3 * 28 + 8) {
			return;
		}
		UUID id = new UUID(readLong(d, 1), readLong(d, 9));
		float[] state = new float[21];
		int p = 17 + 1;
		for (int i = 0; i < 3; ++i) {
			float w = readFloat(d, p + 12);
			state[i * 7] = readFloat(d, p);
			state[i * 7 + 1] = readFloat(d, p + 4);
			state[i * 7 + 2] = readFloat(d, p + 8);
			state[i * 7 + 3] = readFloat(d, p + 16);
			state[i * 7 + 4] = readFloat(d, p + 20);
			state[i * 7 + 5] = readFloat(d, p + 24);
			state[i * 7 + 6] = w;
			p += 28 + 1;
		}
		others.put(id, state);
	}

	private static long readLong(final byte[] d, final int o) {
		long v = 0L;
		for (int i = 0; i < 8; ++i) {
			v = (v << 8) | (d[o + i] & 0xFFL);
		}
		return v;
	}

	private static float readFloat(final byte[] d, final int o) {
		int bits = ((d[o] & 0xFF) << 24) | ((d[o + 1] & 0xFF) << 16) | ((d[o + 2] & 0xFF) << 8) | (d[o + 3] & 0xFF);
		return Float.intBitsToFloat(bits);
	}

	/** Helper for {@link XRControllerInput26#networkPoses}: pose array from a world point and rotation. */
	static float[] pose(final Vec3 rel, final Quaternionf q) {
		return new float[] { (float) rel.x, (float) rel.y, (float) rel.z, q.x, q.y, q.z, q.w };
	}
}

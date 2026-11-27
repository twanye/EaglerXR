package net.lax1dude.eaglercraft.v1_8.webxr;

import com.mojang.serialization.Codec;
import java.util.List;
import net.lax1dude.eaglercraft.v1_8.internal.PlatformWebXR;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/**
 * EaglerXR: in-game VR settings (reachable from Options and from the pause menu).
 *
 * <p>Every option is stored as {@code localStorage['eaglerxr.<key>']} through
 * {@link PlatformWebXR#setConfig} the moment it changes, and read back by
 * {@link XRControllerInput26} (controls) and the web layer (HUD placement, hands).
 * A value forced in {@code globalThis.eaglerXRConfig} still wins over the stored one.
 * Option layout follows Visor / Vivecraft defaults where they have one.
 */
public class VRSettingsScreen extends OptionsSubScreen {

	public static final String HUD_MODE = "hudMode";
	public static final String SHOW_HANDS = "showHands";
	public static final String ROOMSCALE = "roomscaleBreaking";
	public static final String HURT_COMFORT = "hurtCamComfort";
	public static final String VIVECRAFT_NET = "vivecraftNetwork";

	public VRSettingsScreen(final Screen lastScreen, final Options options) {
		super(lastScreen, options, Component.literal("VR Settings"));
	}

	@Override
	protected void addOptions() {
		this.list.addHeader(Component.literal("Comfort and HUD"));
		this.list.addSmall(
			choice("HUD", HUD_MODE, 0, List.of(0, 1, 2), List.of("Face", "Hand", "Floating"),
				"Face: locked in front of your view. Hand: on the back of your off hand. Floating: a panel in front of you that follows your turning lazily."),
			choice("Hands", SHOW_HANDS, 1, List.of(0, 1, 2), List.of("Off", "Both", "Main hand"), "Draws a simple arm at each controller."),
			bool("Hurt camera comfort", HURT_COMFORT, true, "On: no camera tilt/shake when you take damage (as in Vivecraft)."),
			bool("Left handed", "leftHanded", false, "Swaps the main and off hand controllers.")
		);
		this.list.addHeader(Component.literal("Turning"));
		this.list.addSmall(
			choice("Turning", "smoothTurn", 0, List.of(0, 1), List.of("Snap", "Smooth"), "Right stick left/right turns in steps (snap) or continuously (smooth)."),
			slider("Snap angle", "snapTurnDegrees", 45.0F, 1, 12, 15.0F, " deg"),
			slider("Smooth turn speed", "smoothTurnSpeed", 70.0F, 1, 36, 10.0F, " deg/s")
		);
		this.list.addHeader(Component.literal("Movement and interaction"));
		this.list.addSmall(
			choice("Walk direction", "moveRelativeToController", 0, List.of(0, 1), List.of("Head", "Off controller"), "Which way pushing the movement stick forward walks."),
			bool("Auto sprint", "autoSprint", true, "Sprint when the movement stick is pushed past the threshold."),
			slider("Auto sprint threshold", "sprintThreshold", 0.9F, 5, 10, 0.1F, ""),
			bool("Roomscale breaking", ROOMSCALE, true, "Swing your main hand into a block to mine it, or at a mob to hit it (as in Vivecraft). The trigger still works.")
		);
		this.list.addHeader(Component.literal("Multiplayer"));
		this.list.addSmall(
			bool("Vivecraft server support", VIVECRAFT_NET, false,
				"Experimental. On a server with Vivecraft server support, sends your head and hand poses on the vivecraft:data channel so other VR players see you as a VR player. Takes effect on the next join.")
		);
	}

	private static void store(final String key, final float value) {
		PlatformWebXR.setConfig(key, value);
		XRControllerInput26.reloadOptions();
	}

	private static OptionInstance<Boolean> bool(final String caption, final String key, final boolean def, final String tooltip) {
		boolean initial = PlatformWebXR.getConfig(key, def ? 1.0F : 0.0F) != 0.0F;
		return new OptionInstance<>(
			caption,
			OptionInstance.cachedConstantTooltip(Component.literal(tooltip)),
			(c, v) -> CommonComponents.optionStatus(c, v),
			OptionInstance.BOOLEAN_VALUES,
			initial,
			v -> store(key, v ? 1.0F : 0.0F)
		);
	}

	private static OptionInstance<Integer> choice(
		final String caption, final String key, final int def, final List<Integer> values, final List<String> labels, final String tooltip
	) {
		int initial = Math.round(PlatformWebXR.getConfig(key, def));
		if (!values.contains(initial)) {
			initial = def;
		}
		return new OptionInstance<>(
			caption,
			OptionInstance.cachedConstantTooltip(Component.literal(tooltip)),
			(c, v) -> Options.genericValueLabel(c, Component.literal(labels.get(Math.max(0, values.indexOf(v))))),
			new OptionInstance.Enum<>(values, Codec.INT),
			initial,
			v -> store(key, v)
		);
	}

	/** Integer slider from {@code min} to {@code max}; the stored value is {@code step * slider}. */
	private static OptionInstance<Integer> slider(
		final String caption, final String key, final float def, final int min, final int max, final float step, final String unit
	) {
		int initial = Math.round(PlatformWebXR.getConfig(key, def) / step);
		initial = Math.max(min, Math.min(max, initial));
		return new OptionInstance<>(
			caption,
			OptionInstance.noTooltip(),
			(c, v) -> Options.genericValueLabel(c, Component.literal(format(v * step) + unit)),
			new OptionInstance.IntRange(min, max),
			initial,
			v -> store(key, v * step)
		);
	}

	private static String format(final float v) {
		int tenths = Math.round(v * 10.0F);
		return tenths % 10 == 0 ? Integer.toString(tenths / 10) : (tenths / 10) + "." + Math.abs(tenths % 10);
	}
}

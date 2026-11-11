package be.voidguardians.steamu.compat;

import be.voidguardians.steamu.ui.ControlsScreen;
import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/** Mod Menu's "Configure" button opens the SteamU Controls screen. */
public class ModMenuIntegration implements ModMenuApi {
	@Override
	public ConfigScreenFactory<?> getModConfigScreenFactory() {
		return ControlsScreen::new;
	}
}

package net.damoon.core;

import net.minecraft.client.settings.KeyBinding;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.input.Keyboard;

@Mod(modid = DamoonCore.MODID, name = "Damoon Core", version = DamoonCore.VERSION,
        clientSideOnly = true, acceptedMinecraftVersions = "[1.8.9]")
public class DamoonCore {
    public static final String MODID = "damoon";
    public static final String VERSION = "1.0.0";
    public static final Logger LOG = LogManager.getLogger("Damoon");

    public static KeyBinding zoomKey;
    public static KeyBinding menuKey;

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        Config.load();
        zoomKey = new KeyBinding("key.damoon.zoom", Keyboard.KEY_C, "key.categories.damoon");
        menuKey = new KeyBinding("key.damoon.menu", Keyboard.KEY_RSHIFT, "key.categories.damoon");
        ClientRegistry.registerKeyBinding(zoomKey);
        ClientRegistry.registerKeyBinding(menuKey);

        Events events = new Events();
        MinecraftForge.EVENT_BUS.register(events);
        FMLCommonHandler.instance().bus().register(events); // 틱 이벤트 (같은 버스면 중복 등록은 무시됨)
        LOG.info("Damoon Core {} 로드 완료", VERSION);
    }
}

package me.av306.keybindsgaloreplus;

import static me.av306.keybindsgaloreplus.KeybindsGalorePlus.customDataManager;

import me.av306.keybindsgaloreplus.mixin.KeyBindingAccessor;
import me.av306.keybindsgaloreplus.mixin.MinecraftClientAccessor;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.input.KeyInput;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.client.util.NarratorManager;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.GameMode;
import net.minecraft.util.Identifier;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class KeybindSelectorScreen extends Screen {

    private int ticksInScreen = 0;
    private int selectedSectorIndex = -1;
    private boolean mouseDown = false;

    private final InputUtil.Key conflictedKey;

    /** All conflicts for this physical key (unfiltered) */
    private final ArrayList<KeyBinding> conflicts = new ArrayList<>();

    /** Conflicts filtered to only those usable in the current game mode */
    private final ArrayList<KeyBinding> filteredConflicts = new ArrayList<>();

    private int centreX, centreY;
    private float maxRadius;
    private float cancelZoneRadius;

    private static final Pattern MC_ID_PATTERN = Pattern.compile("minecraft:([a-z0-9_./-]+)");

    public KeybindSelectorScreen(InputUtil.Key key) {
        super(NarratorManager.EMPTY);
        this.conflictedKey = key;
        this.conflicts.addAll(KeybindManager.getConflicts(key));
    }

    @Override
    protected void init() {
        this.centreX = this.width / 2;
        this.centreY = this.height / 2;

        this.maxRadius = Math.min(
                (this.centreX * Configurations.PIE_MENU_SCALE) - Configurations.PIE_MENU_MARGIN,
                (this.centreY * Configurations.PIE_MENU_SCALE) - Configurations.PIE_MENU_MARGIN
        );

        this.cancelZoneRadius = this.maxRadius * Configurations.CANCEL_ZONE_SCALE;

        // ---- Filter once (controls whether we show the menu at all) ----
        this.filteredConflicts.clear();

        GameMode mode = (this.client != null && this.client.interactionManager != null)
                ? this.client.interactionManager.getCurrentGameMode()
                : GameMode.SURVIVAL;

        for (KeyBinding kb : this.conflicts) {
            if (isKeybindAllowedInMode(kb, mode)) {
                this.filteredConflicts.add(kb);
            }
        }

        // If nothing is usable in this mode -> close immediately
        if (this.filteredConflicts.isEmpty()) {
            if (this.client != null) this.client.setScreen(null);
            return;
        }

        // If exactly one is usable -> activate instantly, no menu
        if (this.filteredConflicts.size() == 1) {
            activateBinding(this.filteredConflicts.get(0));
            if (this.client != null) this.client.setScreen(null);
        }
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        // If init() auto-activated or auto-closed, don't render anything
        if (this.filteredConflicts.size() <= 1) return;

        double angle = mouseAngle(this.centreX, this.centreY, mouseX, mouseY);
        float distance = MathHelper.sqrt(
                (mouseX - this.centreX) * (mouseX - this.centreX) +
                (mouseY - this.centreY) * (mouseY - this.centreY)
        );

        int sectors = this.filteredConflicts.size();
        float sectorAngle = MathHelper.TAU / sectors;

        this.selectedSectorIndex = (int) (angle / sectorAngle);

        if (distance <= this.cancelZoneRadius) {
            this.selectedSectorIndex = -1;
        }

        renderLabels(context, delta, sectors, sectorAngle);
    }

    // ================== LABELS ==================

    private void renderLabels(DrawContext context, float delta, int sectors, float sectorAngle) {
        for (int i = 0; i < sectors; i++) {
            KeyBinding action = this.filteredConflicts.get(i);

            float radius = calculateRadius(delta, sectors, i);
            float a = (i + 0.5f) * sectorAngle;

            float x = this.centreX + MathHelper.cos(a) * radius;
            float y = this.centreY + MathHelper.sin(a) * radius;

            // Action translation key (key.inventory, key.swapOffhand, etc.)
            String actionKey = getActionTranslationKey(action);

            // Category translation key (key.categories.inventory, key.categories.gameplay, etc.)
            String categoryKey = getCategoryTranslationKey(action.getCategory());

            String categoryText = translateOrFallback(categoryKey, "Misc");
            String actionText   = translateOrFallback(actionKey, "Unknown");

            String keyText = (action.getBoundKeyLocalizedText() != null)
                    ? action.getBoundKeyLocalizedText().getString()
                    : "Unknown";

            String label = categoryText + ": " + actionText + " [" + keyText + "]";

            // Custom data overrides should use actionKey (same as old getTranslationKey())
            if (customDataManager.hasCustomData && actionKey != null) {
                var data = customDataManager.customData.get(actionKey);
                if (data != null) {
                    if (data.hideCategory) {
                        label = actionText + " [" + keyText + "]";
                    }
                    if (data.displayName != null) {
                        label = data.displayName;
                    }
                }
            }

            int textWidth = this.textRenderer.getWidth(label);

            // Clamp X so it doesn't go off-screen
            if (x > this.centreX) {
                x -= Configurations.LABEL_TEXT_INSET;
                if (this.width - x < textWidth) {
                    x -= textWidth - this.width + x;
                }
            } else {
                x -= textWidth - Configurations.LABEL_TEXT_INSET;
                if (x < 0) x = Configurations.LABEL_TEXT_INSET;
            }

            y -= Configurations.LABEL_TEXT_INSET;

            if (this.selectedSectorIndex == i) {
                label = Formatting.UNDERLINE + label;
            }

            context.drawText(
                    this.textRenderer,
                    label,
                    (int) x,
                    (int) y,
                    0xFFFFFFFF,
                    Configurations.LABEL_TEXT_SHADOW
            );
        }
    }

    // ================== FILTERING (GAME MODE) ==================

    private boolean isKeybindAllowedInMode(KeyBinding action, GameMode mode) {
    String actionKey = getActionTranslationKey(action);
    if (actionKey == null) return false;

    boolean spectatorKey = actionKey.startsWith("key.spectator");

    // ---- Not spectator: hide spectator-only actions ----
    if (mode != GameMode.SPECTATOR) {
        return !spectatorKey;
    }

    // ---- Spectator ----
    // Always allow Pick Block in spectator
    if ("key.pickItem".equals(actionKey)) return true;

    // If it's not a spectator key, normally it doesn't make sense in spectator for the conflict set
    // (you can change to "return true" if you want non-spectator binds too)
    if (!spectatorKey) return false;

    // Always allow opening the spectator menu
    if ("key.spectatorMenu".equals(actionKey)) return true;

    // Other spectator sub-actions are only meaningful when the spectator menu screen is open
    // (otherwise you get things like "Select On Hotbar" being shown but unusable)
    try {
        if (this.client != null && this.client.currentScreen != null) {
            // Avoid direct class import to keep mappings stable:
            // net.minecraft.client.gui.screen.SpectatorMenuScreen
            String screenName = this.client.currentScreen.getClass().getName();
            if (screenName.endsWith("SpectatorMenuScreen")) {
                return true;
            }
        }
    } catch (Throwable ignored) { }

    return false;
}


    // ================== ACTIVATION ==================

    private void activateBinding(KeyBinding binding) {
        ((KeyBindingAccessor) binding).setPressed(true);
        ((KeyBindingAccessor) binding).setTimesPressed(1);

        if (this.client != null
                && binding.equals(this.client.options.attackKey)
                && Configurations.ENABLE_ATTACK_WORKAROUND) {
            ((MinecraftClientAccessor) this.client).setAttackCooldown(0);
        }
    }

    private void closePieMenu() {
        if (this.client != null) this.client.setScreen(null);

        if (this.selectedSectorIndex >= 0 && this.selectedSectorIndex < this.filteredConflicts.size()) {
            activateBinding(this.filteredConflicts.get(this.selectedSectorIndex));
        }
    }

    // ================== INPUT ==================

    @Override
    public boolean keyReleased(KeyInput input) {
        if (input.key() == this.conflictedKey.getCode()) {
            closePieMenu();
        }
        return super.keyReleased(input);
    }

    @Override
    public boolean mouseClicked(Click click, boolean focused) {
        this.mouseDown = true;
        return super.mouseClicked(click, focused);
    }

    @Override
    public void tick() {
        this.ticksInScreen++;
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    // ================== UTIL ==================

    private float calculateRadius(float delta, int sectors, int index) {
        float base = Configurations.ANIMATE_PIE_MENU
                ? Math.min((this.ticksInScreen + delta) * 40f, this.maxRadius)
                : this.maxRadius;

        if (this.selectedSectorIndex == index) {
            base *= Configurations.EXPANSION_FACTOR_WHEN_SELECTED;
        }

        return base;
    }

    private static double mouseAngle(int x, int y, int mx, int my) {
        return (MathHelper.atan2(my - y, mx - x) + Math.PI * 2) % (Math.PI * 2);
    }

    // ================== TRANSLATION KEY HELPERS ==================

    /**
     * Gets the ACTION translation key, not the physical-key translation key.
     * We avoid returning key.keyboard.* / key.mouse.* and key.categories.*.
     */
    private static String getActionTranslationKey(KeyBinding binding) {
        // 1) common field names
        String s = (String) readField(binding, "translationKey");
        if (isActionTranslationKey(s)) return s;

        s = (String) readField(binding, "key");
        if (isActionTranslationKey(s)) return s;

        // 2) scan all String fields
        try {
            for (Field f : binding.getClass().getDeclaredFields()) {
                if (f.getType() != String.class) continue;
                f.setAccessible(true);
                Object v = f.get(binding);
                if (v instanceof String str && isActionTranslationKey(str)) {
                    return str;
                }
            }
        } catch (Throwable ignored) { }

        // 3) last resort: if project’s getBoundKeyTranslationKey() happens to be action key in your mappings
        try {
            String fallback = binding.getBoundKeyTranslationKey();
            if (isActionTranslationKey(fallback)) return fallback;
        } catch (Throwable ignored) { }

        return null;
    }

    private static boolean isActionTranslationKey(String s) {
        if (s == null) return false;
        if (!s.startsWith("key.")) return false;
        if (s.startsWith("key.categories.")) return false;
        if (s.startsWith("key.keyboard.")) return false; // physical key
        if (s.startsWith("key.mouse.")) return false;    // physical key
        return true;
    }

    /**
     * Returns something like "key.categories.inventory".
     * Tries:
     * - categoryObj.getId() / id() / field "id" (Identifier)
     * - field "translationKey" (String)
     * - regex parse from toString() (minecraft:<path>)
     */
    private static String getCategoryTranslationKey(Object categoryObj) {
        if (categoryObj == null) return "key.categories.misc";

        // If category itself already has a translationKey string
        Object tk = readField(categoryObj, "translationKey");
        if (tk instanceof String tks && tks.startsWith("key.categories.")) {
            return tks;
        }

        // Try methods that return Identifier
        Identifier id = tryInvokeIdentifier(categoryObj, "getId");
        if (id == null) id = tryInvokeIdentifier(categoryObj, "id");
        if (id == null) {
            Object f = readField(categoryObj, "id");
            if (f instanceof Identifier fid) id = fid;
        }

        if (id != null) {
            return "key.categories." + id.getPath().toLowerCase(Locale.ROOT);
        }

        // Fallback: parse toString() for minecraft:<path>
        String raw = categoryObj.toString();
        if (raw != null) {
            Matcher m = MC_ID_PATTERN.matcher(raw);
            if (m.find()) {
                String path = m.group(1);
                return "key.categories." + path.toLowerCase(Locale.ROOT);
            }
        }

        return "key.categories.misc";
    }

    private static Identifier tryInvokeIdentifier(Object target, String methodName) {
        try {
            Method m = target.getClass().getMethod(methodName);
            m.setAccessible(true);
            Object r = m.invoke(target);
            if (r instanceof Identifier id) return id;
        } catch (Throwable ignored) { }
        return null;
    }

    private static String translateOrFallback(String key, String fallback) {
        if (key == null) return fallback;
        try {
            String s = Text.translatable(key).getString();
            // If translation missing, Minecraft обычно вернёт сам key (не всегда, но часто)
            if (s == null || s.equals(key)) return fallbackOrPretty(key, fallback);
            return s;
        } catch (Throwable t) {
            return fallback;
        }
    }

    private static String fallbackOrPretty(String key, String fallback) {
        if (key == null) return fallback;
        // key.categories.inventory -> Inventory
        int lastDot = key.lastIndexOf('.');
        if (lastDot >= 0 && lastDot + 1 < key.length()) {
            String tail = key.substring(lastDot + 1).replace('_', ' ');
            if (!tail.isEmpty()) {
                String lower = tail.toLowerCase(Locale.ROOT);
                return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
            }
        }
        return fallback;
    }

    private static Object readField(Object target, String fieldName) {
        try {
            Field f = target.getClass().getDeclaredField(fieldName);
            f.setAccessible(true);
            return f.get(target);
        } catch (Throwable ignored) {
            return null;
        }
    }
}

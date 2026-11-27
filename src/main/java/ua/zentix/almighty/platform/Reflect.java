package ua.zentix.almighty.platform;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Access to a declared field or method of a game class by its Mojang name, made accessible. The game runs with
 * Mojang names on every loader this mod supports (NeoForge since 1.20.5, Fabric's unobfuscated 26.x), so no
 * remapping is needed. A missing member is an unchecked {@link IllegalStateException}, as the loaders' helpers throw.
 */
public final class Reflect {
    private Reflect() {}

    public static Field field(Class<?> owner, String name) {
        try {
            Field f = owner.getDeclaredField(name);
            f.setAccessible(true);
            return f;
        } catch (ReflectiveOperationException | RuntimeException e) {
            throw new IllegalStateException("No field " + owner.getName() + "." + name, e);
        }
    }

    public static Method method(Class<?> owner, String name, Class<?>... parameters) {
        try {
            Method m = owner.getDeclaredMethod(name, parameters);
            m.setAccessible(true);
            return m;
        } catch (ReflectiveOperationException | RuntimeException e) {
            throw new IllegalStateException("No method " + owner.getName() + "." + name, e);
        }
    }
}

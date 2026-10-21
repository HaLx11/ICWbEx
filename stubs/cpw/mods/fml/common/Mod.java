package cpw.mods.fml.common;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Compile-time stand-in for Forge's {@code @Mod}.
 *
 * <p>Only the elements ICWbEx actually sets are declared. javac writes just the
 * values that appear in the source onto the class file, and FML's ASM reader fills
 * every element it does not find with its default, so leaving the rest out is
 * equivalent to not specifying them.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Mod {
    String modid();

    String name() default "";

    String version() default "";

    String acceptableRemoteVersions() default "";

    String acceptedMinecraftVersions() default "";

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.METHOD)
    @interface EventHandler {
    }
}

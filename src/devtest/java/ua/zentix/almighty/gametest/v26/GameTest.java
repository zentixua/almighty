package ua.zentix.almighty.gametest.v26;

//? if >=26.1 {
/*import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

// 26.x: the 1.21.1 {@code net.minecraft.gametest.framework.GameTest} the test methods carry (the import is
// replaced on 26.x, stonecutter.gradle), with the same attributes and defaults; {@link GmTestRegistry} registers them.
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface GameTest {
    String template() default "";

    String batch() default "defaultBatch";

    int timeoutTicks() default 100;

    long setupTicks() default 0L;

    boolean required() default true;

    boolean skyAccess() default false;
}
*///?}

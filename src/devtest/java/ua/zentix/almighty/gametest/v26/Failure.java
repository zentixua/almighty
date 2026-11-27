package ua.zentix.almighty.gametest.v26;

//? if >=26.1 {
/*import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.network.chat.Component;

// 26.x: a failed check with a plain message, as 1.21.1's {@code new GameTestAssertException(message)} (the
// constructor is replaced on 26.x, stonecutter.gradle). 26.x's own constructor wants the tick, which the tests'
// static helpers do not have, so the description is the message alone.
public class Failure extends GameTestAssertException {
    public Failure(String message) {
        super(Component.literal(message), 0);
    }

    @Override
    public Component getDescription() {
        return this.message;
    }
}
*///?}

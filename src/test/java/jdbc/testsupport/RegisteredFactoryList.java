package jdbc.testsupport;

import java.util.ArrayList;

/**
 * Kept outside {@code com.landawn.abacus}: custom factories cannot be registered for library classes.
 */
public final class RegisteredFactoryList extends ArrayList<String> {
    private static final long serialVersionUID = 1L;

    private final String constructorState;

    public RegisteredFactoryList(final String constructorState) {
        this.constructorState = constructorState;
    }

    public String getConstructorState() {
        return constructorState;
    }
}

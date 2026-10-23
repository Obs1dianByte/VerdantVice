package com.verdantvice.command;

import java.util.Locale;

final class RecipeCommandRoute {
    private RecipeCommandRoute() {
    }

    static Action resolve(String[] arguments) {
        if (arguments.length == 1) {
            return Action.OPEN_GUIDE;
        }
        if (arguments.length == 2
                && arguments[1].toLowerCase(Locale.ROOT).equals("roll")) {
            return Action.START_ROLLING;
        }
        throw new IllegalArgumentException("recipes_usage");
    }

    enum Action {
        OPEN_GUIDE,
        START_ROLLING
    }
}

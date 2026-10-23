package com.verdantvice.command;

import java.util.List;
import java.util.Set;

/** Plans explicit language commands without depending on a Bukkit player. */
final class LanguageCommandChoices {
    private LanguageCommandChoices() {
    }

    static List<Choice> choices(Set<String> supportedLocales) {
        return supportedLocales.stream().sorted().map(Choice::new).toList();
    }

    record Choice(String locale) {
        Choice {
            if (locale == null || !locale.matches("[a-z]{2}_[A-Z]{2}")) {
                throw new IllegalArgumentException("A language choice requires a valid locale");
            }
        }

        String command() {
            return "/vv language " + locale;
        }
    }
}

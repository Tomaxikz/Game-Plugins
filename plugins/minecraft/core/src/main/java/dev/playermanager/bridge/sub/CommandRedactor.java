package dev.playermanager.bridge.sub;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Redacts arguments of credential-bearing commands before they are emitted as {@code command} events. */
public final class CommandRedactor {
    private static final List<String> DEFAULTS = Arrays.asList(
            "login", "l", "log", "register", "reg", "password", "passwd", "pass", "changepassword", "changepass", "cp",
            "authme", "2fa", "totp", "unregister", "setpassword", "captcha", "email", "setemail", "verify", "auth", "pin",
            "nlogin", "login:", "authme:login", "authme:register");
    private final Set<String> names = new HashSet<String>();

    public CommandRedactor(List<String> extra) {
        names.addAll(DEFAULTS);
        if (extra != null) for (String s : extra) names.add(s.toLowerCase(Locale.ROOT));
    }

    public String redact(String command) {
        String c = command.trim();
        if (c.startsWith("/")) c = c.substring(1);
        int sp = c.indexOf(' ');
        if (sp < 0) return c;
        String head = c.substring(0, sp).toLowerCase(Locale.ROOT);
        String bare = head.indexOf(':') >= 0 ? head.substring(head.indexOf(':') + 1) : head;
        if (names.contains(head) || names.contains(bare)) return c.substring(0, sp) + " <redacted>";
        return c;
    }
}

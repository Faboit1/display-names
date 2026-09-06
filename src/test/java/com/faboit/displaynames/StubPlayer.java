package com.faboit.displaynames;

import org.bukkit.entity.Player;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Set;

/**
 * The smallest {@link Player} that condition tests need.
 *
 * <p>{@code Player} is an interface with several hundred methods and no usable implementation
 * outside a running server, so this answers the two calls conditions actually make - a name for
 * messages and a permission lookup - and refuses everything else loudly. A test that starts
 * needing more of the API is a test that has drifted into needing a server.
 */
public final class StubPlayer {

    private StubPlayer() {
    }

    public static Player named(String name, String... permissions) {
        Set<String> held = Set.of(permissions);
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "getName" -> name;
            // hasPermission is overloaded; only the String form is used here.
            case "hasPermission" -> args != null && args.length == 1 && args[0] instanceof String node
                    && held.contains(node);
            case "equals" -> proxy == args[0];
            case "hashCode" -> System.identityHashCode(proxy);
            case "toString" -> "StubPlayer(" + name + ")";
            default -> throw new UnsupportedOperationException(
                    "StubPlayer does not implement " + method.getName() + "()");
        };
        return (Player) Proxy.newProxyInstance(StubPlayer.class.getClassLoader(),
                new Class<?>[] {Player.class}, handler);
    }
}

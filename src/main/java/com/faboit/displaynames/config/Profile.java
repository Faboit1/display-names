package com.faboit.displaynames.config;

import com.faboit.displaynames.text.NametagTemplate;

/**
 * A permission-gated nametag format together with the appearance it is rendered with.
 *
 * @param id         config key, used in messages
 * @param permission permission a player must have to receive this profile, {@code null} for the default
 * @param priority   higher wins when a player matches several profiles
 * @param template   the compiled format
 * @param display    appearance for this profile; the same instance as the global one unless the
 *                   profile overrode something, which lets a changed appearance be spotted by
 *                   identity alone
 */
public record Profile(String id, String permission, int priority, NametagTemplate template,
                      DisplayOptions display) {
}

package com.faboit.displaynames.config;

import com.faboit.displaynames.condition.Condition;
import com.faboit.displaynames.text.NametagTemplate;

/**
 * A gated nametag format together with the appearance it is rendered with.
 *
 * <p>A profile applies when the player holds its permission <em>and</em> satisfies its condition;
 * either may be absent, and a profile needs at least one of them. That pairing is what makes
 * settings per-player: a condition does not only pick text, it picks the whole profile, and with
 * it the offset, scale, billboard and render distance the tag is built with.
 *
 * @param id         config key, used in messages
 * @param permission permission a player must have, {@code null} when the profile is not gated by one
 * @param priority   higher wins when a player matches several profiles
 * @param condition  condition a player must satisfy, {@code null} when there is none
 * @param template   the compiled format
 * @param display    appearance for this profile; the same instance as the global one unless the
 *                   profile overrode something, which lets a changed appearance be spotted by
 *                   identity alone
 */
public record Profile(String id, String permission, int priority, Condition condition,
                      NametagTemplate template, DisplayOptions display) {
}

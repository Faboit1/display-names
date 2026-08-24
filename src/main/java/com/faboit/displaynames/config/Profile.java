package com.faboit.displaynames.config;

import com.faboit.displaynames.text.NametagTemplate;

/**
 * A permission-gated nametag format.
 *
 * @param id         config key, used in messages
 * @param permission permission a player must have to receive this format
 * @param priority   higher wins when a player matches several profiles
 * @param template   the compiled format
 */
public record Profile(String id, String permission, int priority, NametagTemplate template) {
}

/*
 * Talogram mod - channel subscription gate.
 * Verifies membership in the @aljokeruserbot channel before opening the Joker settings.
 */

package org.telegram.messenger;

import android.content.Context;

import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;

public final class TalogramSubscription {

    public static final String CHANNEL_USERNAME = "aljokeruserbot";
    public static final String CHANNEL_LINK = "https://t.me/aljokeruserbot";

    private static final String PREFS = "talogram_settings";
    private static final String KEY_REQUIRE_SUBSCRIPTION = "joker_require_subscription";
    private static final String KEY_CHANNEL_SUBSCRIBED = "joker_channel_subscribed";

    private TalogramSubscription() {
    }

    public static boolean isGateEnabled() {
        if (ApplicationLoader.applicationContext == null) {
            return true;
        }
        return ApplicationLoader.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_REQUIRE_SUBSCRIPTION, true);
    }

    public static boolean isSubscribedCached() {
        if (ApplicationLoader.applicationContext == null) {
            return false;
        }
        return ApplicationLoader.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_CHANNEL_SUBSCRIBED, false);
    }

    public static void saveSubscribed(boolean value) {
        if (ApplicationLoader.applicationContext == null) {
            return;
        }
        ApplicationLoader.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_CHANNEL_SUBSCRIBED, value).apply();
    }

    public static void checkSubscription(int currentAccount, Utilities.Callback<Boolean> callback) {
        if (ApplicationLoader.applicationContext == null) {
            callback.run(isSubscribedCached());
            return;
        }
        TLRPC.TL_contacts_resolveUsername req = new TLRPC.TL_contacts_resolveUsername();
        req.username = CHANNEL_USERNAME;
        ConnectionsManager.getInstance(currentAccount).sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            if (error != null || !(response instanceof TLRPC.TL_contacts_resolvedPeer)) {
                callback.run(isSubscribedCached());
                return;
            }
            TLRPC.TL_contacts_resolvedPeer resolved = (TLRPC.TL_contacts_resolvedPeer) response;
            TLRPC.Chat channel = null;
            if (resolved.chats != null) {
                for (int a = 0; a < resolved.chats.size(); a++) {
                    TLRPC.Chat chat = resolved.chats.get(a);
                    if (chat != null && ChatObject.isChannel(chat)) {
                        channel = chat;
                        break;
                    }
                }
                if (channel == null && !resolved.chats.isEmpty()) {
                    channel = resolved.chats.get(0);
                }
            }
            if (channel == null) {
                callback.run(isSubscribedCached());
                return;
            }
            TLRPC.TL_channels_getParticipant req2 = new TLRPC.TL_channels_getParticipant();
            TLRPC.TL_inputChannel inputChannel = new TLRPC.TL_inputChannel();
            inputChannel.channel_id = channel.id;
            inputChannel.access_hash = channel.access_hash;
            req2.channel = inputChannel;
            req2.participant = MessagesController.getInstance(currentAccount)
                    .getInputPeer(UserConfig.getInstance(currentAccount).getClientUserId());
            ConnectionsManager.getInstance(currentAccount).sendRequest(req2, (res2, err2) -> AndroidUtilities.runOnUIThread(() -> {
                if (err2 != null) {
                    if (err2.code == 400 || err2.code == 403) {
                        saveSubscribed(false);
                        callback.run(false);
                        return;
                    }
                    callback.run(isSubscribedCached());
                    return;
                }
                boolean subscribed = true;
                if (res2 instanceof TLRPC.TL_channels_channelParticipant) {
                    TLObject participant = ((TLRPC.TL_channels_channelParticipant) res2).participant;
                    if (participant != null) {
                        String simple = participant.getClass().getSimpleName();
                        if (simple != null && (simple.contains("Left") || simple.contains("Banned"))) {
                            subscribed = false;
                        }
                    }
                }
                saveSubscribed(subscribed);
                callback.run(subscribed);
            }));
        }));
    }
}

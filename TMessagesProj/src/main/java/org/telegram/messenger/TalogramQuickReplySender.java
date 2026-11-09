/*
 * Talogram mod - sends a local quick reply to a dialog.
 * Everything happens locally through the normal send pipeline.
 */

package org.telegram.messenger;

import android.text.TextUtils;

import java.io.File;
import java.util.ArrayList;

public final class TalogramQuickReplySender {

    private TalogramQuickReplySender() {
    }

    public static void send(int currentAccount, long dialogId, TalogramSettings.QuickReply reply) {
        if (reply == null) {
            return;
        }
        try {
            sendInternal(currentAccount, dialogId, reply);
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private static void sendInternal(int currentAccount, long dialogId, TalogramSettings.QuickReply reply) {
        String text = reply.text == null ? "" : reply.text.trim();
        boolean hasMedia = reply.hasMedia();
        if (hasMedia) {
            File file = TalogramSettings.quickReplyMediaFile(reply.mediaFileName);
            if (file == null || !file.exists()) {
                if (!TextUtils.isEmpty(text)) {
                    sendText(currentAccount, dialogId, text);
                }
                return;
            }
            SendMessagesHelper.SendingMediaInfo info = new SendMessagesHelper.SendingMediaInfo();
            info.path = file.getAbsolutePath();
            info.imagePath = info.path;
            info.isVideo = reply.mediaType == TalogramSettings.MEDIA_TYPE_VIDEO;
            info.caption = text.isEmpty() ? null : text;
            ArrayList<SendMessagesHelper.SendingMediaInfo> list = new ArrayList<>();
            list.add(info);
            SendMessagesHelper.prepareSendingMedia(
                    AccountInstance.getInstance(currentAccount),
                    list,
                    dialogId,
                    null,
                    null,
                    null,
                    null,
                    false,
                    false,
                    null,
                    true,
                    0,
                    0,
                    0,
                    false,
                    null,
                    null,
                    0,
                    false,
                    0,
                    0,
                    null
            );
        } else {
            if (TextUtils.isEmpty(text)) {
                return;
            }
            sendText(currentAccount, dialogId, text);
        }
    }

    private static void sendText(int currentAccount, long dialogId, String text) {
        SendMessagesHelper.SendMessageParams params = SendMessagesHelper.SendMessageParams.of(
                text,
                dialogId,
                null,
                null,
                null,
                false,
                null,
                null,
                null,
                true,
                0,
                0,
                null,
                false
        );
        params.monoForumPeer = 0;
        params.sendMessageChatArguments = null;
        SendMessagesHelper.getInstance(currentAccount).sendMessage(params);
    }
}

//
// Wire
// Copyright (C) 2016 Wire Swiss GmbH
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
// GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License
// along with this program. If not, see http://www.gnu.org/licenses/.
//

package com.wire.bots.sdk.server;

import com.waz.model.Messages;
import com.wire.bots.sdk.MessageHandlerBase;
import com.wire.bots.sdk.WireClient;
import com.wire.bots.sdk.models.*;
import com.wire.bots.sdk.tools.Logger;

import java.util.concurrent.ConcurrentHashMap;

/**
 */
public class GenericMessageProcessor {
    private final static ConcurrentHashMap<String, Messages.Asset.Original> originals = new ConcurrentHashMap<>();
    private final static ConcurrentHashMap<String, Messages.Asset.RemoteData> remotes = new ConcurrentHashMap<>();

    private final WireClient client;
    private final MessageHandlerBase handler;

    public GenericMessageProcessor(WireClient client, MessageHandlerBase handler) {
        this.client = client;
        this.handler = handler;
    }

    private static void uploaded(MessageAssetBase msg, Messages.Asset.RemoteData uploaded) {
        msg.setAssetKey(uploaded.getAssetId());
        msg.setAssetToken(uploaded.hasAssetToken() ? uploaded.getAssetToken() : null);
        msg.setOtrKey(uploaded.getOtrKey().toByteArray());
        msg.setSha256(uploaded.getSha256().toByteArray());
    }

    private static void origin(MessageAssetBase msg, Messages.Asset.Original original) {
        msg.setMimeType(original.getMimeType());
        msg.setSize(original.getSize());
        msg.setName(original.hasName() ? original.getName() : null);
    }

    public void cleanUp(String messageId) {
        remotes.remove(messageId);
        originals.remove(messageId);
    }

    public boolean process(String userId, String sender, Messages.GenericMessage generic) {
        String messageId = generic.getMessageId();
        String convId = client.getConversationId();

        Messages.Text text = null;
        Messages.Asset asset = null;

        Logger.debug("msgId: %s hasText: %s, hasAsset: %s",
                messageId,
                generic.hasText(),
                generic.hasAsset());
        // Text
        if (generic.hasText()) {
            text = generic.getText();
        }

        // Assets
        if (generic.hasAsset()) {
            asset = generic.getAsset();
        }

        // Ephemeral messages
        if (generic.hasEphemeral()) {
            Messages.Ephemeral ephemeral = generic.getEphemeral();
            if (ephemeral.hasText()) {
                text = ephemeral.getText();
            }

            if (ephemeral.hasAsset()) {
                asset = ephemeral.getAsset();
            }
        }

        // Edit message
        if (generic.hasEdited() && generic.getEdited().hasText()) {
            Messages.MessageEdit edited = generic.getEdited();
            TextMessage msg = new TextMessage(edited.getReplacingMessageId(), convId, sender, userId);
            msg.setText(edited.getText().getContent());

            handler.onEditText(client, msg);
            return true;
        }

        // Text
        if (text != null && text.hasContent() && text.getLinkPreviewList().isEmpty()) {
            TextMessage msg = new TextMessage(messageId, convId, sender, userId);
            msg.setText(text.getContent());

            handler.onText(client, msg);
            return true;
        }

        if (generic.hasCalling()) {
            Messages.Calling calling = generic.getCalling();
            if (calling.hasContent()) {
                String content = calling.getContent();
                handler.onCalling(client, userId, sender, content);
            }
            return true;
        }

        if (generic.hasDeleted()) {
            String delMsgId = generic.getDeleted().getMessageId();
            TextMessage msg = new TextMessage(delMsgId, convId, sender, userId);

            handler.onDelete(client, msg);
            return true;
        }

        // Assets
        if (asset != null) {
            Logger.debug("Asset: msgId: %s hasOriginal: %s, hasUploaded: %s",
                    messageId,
                    asset.hasOriginal(),
                    asset.hasUploaded());

            if (asset.hasUploaded()) {
                remotes.put(messageId, asset.getUploaded());
            }

            if (asset.hasOriginal()) {
                originals.put(messageId, asset.getOriginal());
            }

            Messages.Asset.Original original = originals.get(messageId);
            Messages.Asset.RemoteData remoteData = remotes.get(messageId);

            if (original != null && remoteData != null) {

                Logger.debug("Original: msgId: %s hasAudio: %s, hasVideo: %s, hasImage: %s",
                        messageId,
                        original.hasAudio(),
                        original.hasVideo(),
                        original.hasImage());

                MessageAssetBase base = new MessageAssetBase(messageId, convId, sender, userId);
                origin(base, original);
                uploaded(base, remoteData);

                if (original.hasImage()) {
                    ImageMessage msg = new ImageMessage(base, original.getImage());

                    handler.onImage(client, msg);
                    return true;
                }
                if (original.hasAudio()) {
                    AudioMessage msg = new AudioMessage(base, original.getAudio());

                    handler.onAudio(client, msg);
                    return true;
                }
                if (original.hasVideo()) {
                    VideoMessage msg = new VideoMessage(base, original.getVideo());

                    handler.onVideo(client, msg);
                    return true;
                }
                {
                    AttachmentMessage msg = new AttachmentMessage(base);

                    handler.onAttachment(client, msg);
                    return true;
                }
            }
        }

        return false;
    }
}

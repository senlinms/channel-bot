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

package com.wire.bots.channels.resource;

import com.wire.bots.channels.Service;
import com.wire.bots.channels.model.Channel;
import com.wire.bots.channels.model.Config;
import com.wire.bots.sdk.*;
import com.wire.bots.sdk.server.model.NewBot;
import com.wire.bots.sdk.server.model.NewBotResponseModel;

import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.io.File;

@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Path("/channels/{name}/bots")
public class BotsResource {
    private final MessageHandlerBase handler;
    private final Configuration conf;
    private final ClientRepo repo;

    public BotsResource(MessageHandlerBase handler, ClientRepo repo, Config conf) {
        this.handler = handler;
        this.conf = conf;
        this.repo = repo;
    }

    @POST
    public Response newBot(@HeaderParam("Authorization") String auth,
                           @PathParam("name") String channelName,
                           NewBot newBot) throws Exception {

        Channel channel = Service.dbManager.getChannel(channelName);
        if (!Util.compareTokens(auth, channel.token)) {
            Logger.warning("Invalid Authorization for the channel: %s.", channelName);
            return Response.
                    status(403).
                    build();
        }

        Logger.info("New Bot: %s for the Channel: %s, Origin: %s", newBot.id, channelName, newBot.origin.handle);

        Service.dbManager.insertNewBot(newBot.id, channelName, newBot.origin.name, newBot.origin.id, newBot.conversation.id);

        if (newBot.origin.id.equals(channel.origin) && channel.admin == null) {
            Logger.info("Setting admin conv %s for the channel: %s", newBot.id, channelName);
            Service.dbManager.updateChannel(channelName, "Admin", newBot.id);
        } else if (!handler.onNewBot(newBot)) {
            return Response.
                    status(409).
                    build();
        }

        String path = String.format("%s/%s", conf.getCryptoDir(), newBot.id);
        File dir = new File(path);
        if (!dir.mkdirs())
            Logger.warning("Failed to create dir: %s", dir.getAbsolutePath());

        Util.writeLine(newBot.client, new File(path + "/client.id"));
        Util.writeLine(newBot.token, new File(path + "/token.id"));
        Util.writeLine(newBot.conversation.id, new File(path + "/conversation.id"));

        NewBotResponseModel ret = new NewBotResponseModel();
        ret.name = channelName;

        WireClient client = repo.getFactory().createClient(
                newBot.id,
                newBot.conversation.id,
                newBot.client,
                newBot.token);

        ret.lastPreKey = client.newLastPreKey();
        ret.preKeys = client.newPreKeys(0, newBot.conversation.members.size() * 8);

        client.close();

        return Response.
                ok(ret).
                status(201).
                build();
    }
}
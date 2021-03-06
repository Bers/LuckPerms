/*
 * This file is part of LuckPerms, licensed under the MIT License.
 *
 *  Copyright (c) lucko (Luck) <luck@lucko.me>
 *  Copyright (c) contributors
 *
 *  Permission is hereby granted, free of charge, to any person obtaining a copy
 *  of this software and associated documentation files (the "Software"), to deal
 *  in the Software without restriction, including without limitation the rights
 *  to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 *  copies of the Software, and to permit persons to whom the Software is
 *  furnished to do so, subject to the following conditions:
 *
 *  The above copyright notice and this permission notice shall be included in all
 *  copies or substantial portions of the Software.
 *
 *  THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 *  IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 *  FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 *  AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *  LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *  OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 *  SOFTWARE.
 */

package me.lucko.luckperms.common.commands.generic.meta;

import me.lucko.luckperms.api.ChatMetaType;
import me.lucko.luckperms.api.Contexts;
import me.lucko.luckperms.api.DataMutateResult;
import me.lucko.luckperms.api.context.MutableContextSet;
import me.lucko.luckperms.common.actionlog.ExtendedLogEntry;
import me.lucko.luckperms.common.caching.type.MetaAccumulator;
import me.lucko.luckperms.common.command.CommandResult;
import me.lucko.luckperms.common.command.abstraction.CommandException;
import me.lucko.luckperms.common.command.abstraction.SharedSubCommand;
import me.lucko.luckperms.common.command.access.ArgumentPermissions;
import me.lucko.luckperms.common.command.access.CommandPermission;
import me.lucko.luckperms.common.command.utils.ArgumentParser;
import me.lucko.luckperms.common.command.utils.MessageUtils;
import me.lucko.luckperms.common.command.utils.StorageAssistant;
import me.lucko.luckperms.common.locale.LocaleManager;
import me.lucko.luckperms.common.locale.command.CommandSpec;
import me.lucko.luckperms.common.locale.message.Message;
import me.lucko.luckperms.common.model.Group;
import me.lucko.luckperms.common.model.PermissionHolder;
import me.lucko.luckperms.common.node.factory.NodeFactory;
import me.lucko.luckperms.common.plugin.LuckPermsPlugin;
import me.lucko.luckperms.common.sender.Sender;
import me.lucko.luckperms.common.utils.Predicates;
import me.lucko.luckperms.common.utils.TextUtils;

import net.kyori.text.TextComponent;
import net.kyori.text.event.HoverEvent;

import java.util.List;
import java.util.OptionalInt;

public class MetaSetChatMeta extends SharedSubCommand {
    private final ChatMetaType type;

    public MetaSetChatMeta(LocaleManager locale, ChatMetaType type) {
        super(
                type == ChatMetaType.PREFIX ? CommandSpec.META_SETPREFIX.localize(locale) : CommandSpec.META_SETSUFFIX.localize(locale),
                "set" + type.name().toLowerCase(),
                type == ChatMetaType.PREFIX ? CommandPermission.USER_META_SET_PREFIX : CommandPermission.USER_META_SET_SUFFIX,
                type == ChatMetaType.PREFIX ? CommandPermission.GROUP_META_SET_PREFIX : CommandPermission.GROUP_META_SET_SUFFIX,
                Predicates.is(0)
        );
        this.type = type;
    }

    @Override
    public CommandResult execute(LuckPermsPlugin plugin, Sender sender, PermissionHolder holder, List<String> args, String label, CommandPermission permission) throws CommandException {
        if (ArgumentPermissions.checkModifyPerms(plugin, sender, permission, holder)) {
            Message.COMMAND_NO_PERMISSION.send(sender);
            return CommandResult.NO_PERMISSION;
        }

        int priority = ArgumentParser.parseIntOrElse(0, args, Integer.MIN_VALUE);
        String meta;
        MutableContextSet context;

        if (priority == Integer.MIN_VALUE) {
            // priority wasn't defined, meta is at index 0, contexts at index 1
            meta = ArgumentParser.parseString(0, args);
            context = ArgumentParser.parseContext(1, args, plugin);
        } else {
            // priority was defined, meta should be at index 1, contexts at index 2
            if (args.size() <= 1) {
                sendDetailedUsage(sender);
                return CommandResult.INVALID_ARGS;
            }

            meta = ArgumentParser.parseString(1, args);
            context = ArgumentParser.parseContext(2, args, plugin);
        }

        if (ArgumentPermissions.checkContext(plugin, sender, permission, context) ||
                ArgumentPermissions.checkGroup(plugin, sender, holder, context)) {
            Message.COMMAND_NO_PERMISSION.send(sender);
            return CommandResult.NO_PERMISSION;
        }

        // remove all other prefixes/suffixes set in these contexts
        holder.removeIf(context, this.type::matches);

        // determine the priority to set at
        if (priority == Integer.MIN_VALUE) {
            MetaAccumulator metaAccumulator = holder.accumulateMeta(null, Contexts.of(context, Contexts.global().getSettings()));
            metaAccumulator.complete();
            priority = metaAccumulator.getChatMeta(this.type).keySet().stream().mapToInt(e -> e).max().orElse(0) + 1;

            if (holder instanceof Group) {
                OptionalInt weight = holder.getWeight();
                if (weight.isPresent() && weight.getAsInt() > priority) {
                    priority = weight.getAsInt();
                }
            }
        }

        DataMutateResult result = holder.setPermission(NodeFactory.buildChatMetaNode(this.type, priority, meta).withExtraContext(context).build());
        if (result.asBoolean()) {
            TextComponent.Builder builder = Message.ADD_CHATMETA_SUCCESS.asComponent(plugin.getLocaleManager(), holder.getFormattedDisplayName(), this.type.name().toLowerCase(), meta, priority, MessageUtils.contextSetToString(plugin.getLocaleManager(), context)).toBuilder();
            HoverEvent event = new HoverEvent(HoverEvent.Action.SHOW_TEXT, TextUtils.fromLegacy(
                    "¥3Raw " + this.type.name().toLowerCase() + ": ¥r" + meta,
                    '¥'
            ));
            builder.applyDeep(c -> c.hoverEvent(event));
            sender.sendMessage(builder.build());

            ExtendedLogEntry.build().actor(sender).acted(holder)
                    .action("meta" , "set" + this.type.name().toLowerCase(), priority, meta, context)
                    .build().submit(plugin, sender);

            StorageAssistant.save(holder, sender, plugin);
            return CommandResult.SUCCESS;
        } else {
            Message.ALREADY_HAS_CHAT_META.send(sender, holder.getFormattedDisplayName(), this.type.name().toLowerCase(), meta, priority, MessageUtils.contextSetToString(plugin.getLocaleManager(), context));
            return CommandResult.STATE_ERROR;
        }
    }
}

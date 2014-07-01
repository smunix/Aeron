/*
 * Copyright 2014 Real Logic Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package uk.co.real_logic.aeron.util.collections;

import java.util.HashMap;
import java.util.Map;

import static uk.co.real_logic.aeron.util.collections.CollectionUtil.getOrDefault;

/**
 * Map for storing information about channels. These are keyed
 * by a triple of destination/session/channel.
 */
public class ChannelMap<D, C>
{
    private final Map<D, Long2ObjectHashMap<Long2ObjectHashMap<C>>> map;

    public ChannelMap()
    {
        map = new HashMap<>();
    }

    public C get(final D destination, final long sessionId, final long channelId)
    {
        final Long2ObjectHashMap<Long2ObjectHashMap<C>> sessionMap = map.get(destination);
        if (sessionMap == null)
        {
            return null;
        }

        final Long2ObjectHashMap<C> channelMap = sessionMap.get(sessionId);
        if (channelMap == null)
        {
            return null;
        }

        return channelMap.get(channelId);
    }

    public C put(final D destination, final long sessionId, final long channelId, final C value)
    {
        final Long2ObjectHashMap<Long2ObjectHashMap<C>> sessionMap
            = getOrDefault(map, destination, ignore -> new Long2ObjectHashMap<>());
        final Long2ObjectHashMap<C> channelMap = sessionMap.getOrDefault(sessionId, Long2ObjectHashMap::new);
        return channelMap.put(channelId, value);
    }

    public C remove(final D destination, final long sessionId, final long channelId)
    {
        final Long2ObjectHashMap<Long2ObjectHashMap<C>> sessionMap = map.get(destination);
        if (sessionMap == null)
        {
            return null;
        }

        final Long2ObjectHashMap<C> channelMap = sessionMap.get(sessionId);
        if (channelMap == null)
        {
            return null;
        }

        C value = channelMap.remove(channelId);

        if (channelMap.isEmpty())
        {
            sessionMap.remove(sessionId);
            if (sessionMap.isEmpty())
            {
                map.remove(destination);
            }
        }

        return value;
    }

    public boolean isEmpty(final D destination)
    {
        return !map.containsKey(destination);
    }

    public interface ChannelHandler<D, T>
    {
        void accept(final D destination, final Long sessionId, final Long channelId, final T value);
    }

    @SuppressWarnings("unchecked")
    public void forEach(final ChannelHandler subscriber)
    {
        map.forEach((destination, sessionMap) ->
        {
            sessionMap.forEach((sessionId, channelMap) ->
            {
                channelMap.forEach((channelId, value) -> subscriber.accept(destination, sessionId, channelId, value));
            });
        });
    }
}

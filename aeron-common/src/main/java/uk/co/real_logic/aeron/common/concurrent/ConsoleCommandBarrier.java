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
package uk.co.real_logic.aeron.common.concurrent;

import java.io.Console;

/**
 * Barrier to block the calling thread until a command is given on the {@link Console}
 */
public class ConsoleCommandBarrier
{
    final String label;

    /**
     * Create a barrier that will display the provided label and interact via the {@link Console}.
     *
     * @param label to prompt the user.
     */
    public ConsoleCommandBarrier(final String label)
    {
        this.label = label;
    }

    /**
     * Await for input that matches the provided command.
     *
     * @param cmd to be awaited from the input stream.
     */
    public void await(final String cmd)
    {
        final Console console = System.console();
        if (null == console)
        {
            throw new IllegalStateException("Console is not available");
        }

        while (true)
        {
            console.printf("\n%s : ", label).flush();

            final String line = console.readLine();
            if (cmd.equalsIgnoreCase(line))
            {
                break;
            }
        }
    }
}
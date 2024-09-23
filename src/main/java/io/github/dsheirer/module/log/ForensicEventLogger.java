/*******************************************************************************
 *     SDR Trunk 
 *     Copyright (C) 2014 Dennis Sheirer
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <http://www.gnu.org/licenses/>
 ******************************************************************************/
package io.github.dsheirer.module.log;

import io.github.dsheirer.sample.Listener;
import io.github.dsheirer.util.TimeStamp;

import java.nio.file.Path;

/**
 * Simple text event logger to capture forensic details.
 */
public class ForensicEventLogger extends EventLogger implements Listener<String>
{
    /**
     * Constructs an instance.
     * @param logDirectory
     * @param fileNameSuffix
     * @param type
     * @param frequency
     */
    public ForensicEventLogger(Path logDirectory, String fileNameSuffix, long frequency)
    {
        super(logDirectory, fileNameSuffix, frequency);
    }

    @Override
    public void reset()
    {
    }

    @Override
    public void receive(String message)
    {
        StringBuilder sb = new StringBuilder();
        sb.append(TimeStamp.getTimeStamp(System.currentTimeMillis(), " ")).append(" ").append(message);
        write(sb.toString());
    }

    @Override
    public String getHeader()
    {
        return "Forensic Event Logger for frequency [" + mFrequency + "]\n";
    }
}

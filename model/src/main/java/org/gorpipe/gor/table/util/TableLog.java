/*
 *  BEGIN_COPYRIGHT
 *
 *  Copyright (C) 2011-2013 deCODE genetics Inc.
 *  Copyright (C) 2013-2019 WuXi NextCode Inc.
 *  All Rights Reserved.
 *
 *  GORpipe is free software: you can redistribute it and/or modify
 *  it under the terms of the AFFERO GNU General Public License as published by
 *  the Free Software Foundation.
 *
 *  GORpipe is distributed "AS-IS" AND WITHOUT ANY WARRANTY OF ANY KIND,
 *  INCLUDING ANY IMPLIED WARRANTY OF MERCHANTABILITY,
 *  NON-INFRINGEMENT, OR FITNESS FOR A PARTICULAR PURPOSE. See
 *  the AFFERO GNU General Public License for the complete license terms.
 *
 *  You should have received a copy of the AFFERO GNU General Public License
 *  along with GORpipe.  If not, see <http://www.gnu.org/licenses/agpl-3.0.html>
 *
 *  END_COPYRIGHT
 */

package org.gorpipe.gor.table.util;

import org.gorpipe.exceptions.GorResourceException;
import org.gorpipe.exceptions.GorSystemException;
import org.gorpipe.gor.model.FileReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Helper class to keep track of action on a table and save those to file.
 */
public class TableLog {
    private static final Logger log = LoggerFactory.getLogger(TableLog.class);

    private int version = 1;

    public static final String LOG_FILE = "action.log";
    public enum LogAction {
        INSERT,
        DELETE,
        ADDTOBUCKET,
        REMOVEFROMBUCKET
    }

    private final String logDir;  // Location of log files.
    private final String logFilePath;
    private final DateTimeFormatter formatter;
    protected List<String> unCommittedActions = Collections.synchronizedList(new ArrayList<>());

    /**
     * Constructor
     * @param logDir    folder to store the log file.
     */
    public TableLog(String logDir) {
        this.logDir = logDir;
        this.logFilePath = PathUtils.resolve(this.logDir, LOG_FILE);
        this.formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    }

    /**
     * Create new log record after entry is updated.
     * @param action    action performed
     * @param argument  action argument (can be null if all info is in the entry object)
     */
    public void logAfter(String tableVersion, LogAction action, String argument, String line) {
        String logLine = String.format("%s\t%d\t%s\t%s\t%s", LocalDateTime.now().format(formatter), version, tableVersion, action.toString(), argument);
        logLine += String.format("\t%s", line);
        logLine += "\n";
        unCommittedActions.add(logLine);
    }

    /**
     * Save the uncommitted log lines to file, and clear the list of uncommitted log lines.
     */
    public void commit(FileReader fileReader) {
        log.debug("Committing {} actions to log file {}", unCommittedActions.size(), logFilePath);
        if (!fileReader.exists(PathUtils.formatUri(this.logDir)) || !fileReader.isDirectory(PathUtils.formatUri(this.logDir))) {
            throw new GorResourceException(String.format("Log '%s'folder does not exits", this.logDir), logFilePath.toString(), null);
        }

        try (Writer destination = new BufferedWriter(
                new OutputStreamWriter(fileReader.getOutputStream(PathUtils.formatUri((this.logFilePath)), true)))) {

            for (String logLine : unCommittedActions) {
                destination.write(logLine);
            }
            unCommittedActions.clear();
        } catch (IOException e) {
            throw new GorResourceException(String.format("Could not save table log %s", logFilePath), logFilePath.toString(), e);
        }
    }

    public String getLogDir() {
        return logDir;
    }
}

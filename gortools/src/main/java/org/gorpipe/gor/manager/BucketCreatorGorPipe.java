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

package org.gorpipe.gor.manager;

import org.gorpipe.gor.table.GorPipeUtils;
import org.gorpipe.gor.table.dictionary.DictionaryTable;
import org.gorpipe.gor.table.dictionary.DictionaryEntry;
import org.gorpipe.gor.table.util.PathUtils;

import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Helper class to create bucket file from bucket description.
 */
public class BucketCreatorGorPipe<T extends DictionaryEntry> implements BucketCreator<T>  {

    public static final int DEFAULT_NUMBER_WORKERS = 4;

    private final int workers;

    public BucketCreatorGorPipe() {
        this(DEFAULT_NUMBER_WORKERS);
    }

    public BucketCreatorGorPipe(int workers) {
        this.workers = workers;
    }

    @Override
    public void createBucketsForBucketDir(DictionaryTable table, Map<String, List<T>> bucketsToCreate,
                                          String absBucketDir, int maxBucketCount, Consumer<String> callback) {

        Map<String, List<T>> bucketsToCreateBatch = new HashMap<>();
        int bucketsCreated = 0;
        for (Map.Entry<String, List<T>> b2c : bucketsToCreate.entrySet()) {
            bucketsToCreateBatch.put(b2c.getKey(), b2c.getValue());
            bucketsCreated++;

            if (bucketsToCreateBatch.size() >= maxBucketCount || bucketsToCreate.size() == bucketsCreated) {
                // Build the gor query (gorpipe)
                String gorPipeCommand = createBucketizeGorCommandForBucketDir(bucketsToCreate, absBucketDir, table, maxBucketCount);
                GorPipeUtils.executeGorPipeForSideEffects(gorPipeCommand, workers, table.getProjectPath(), table.getSecurityContext());

                // Call the callback for each bucket created.
                for (String bucket : bucketsToCreateBatch.keySet()) {
                    callback.accept(bucket);
                }
                bucketsToCreateBatch.clear();
            }
        }
    }

    private String createBucketizeGorCommandForBucketDir(Map<String, List<T>> bucketsToCreate, String absBucketDir,
                                                         DictionaryTable table, int maxBucketCount) {
        // NOTE:  Can not use pgor with the write command !! Will only get one chromosome.
        // Tag based, does not work if we are adding more files with same alias, why not?.
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, List<T>> b2c : bucketsToCreate.entrySet()) {
            String bucket = b2c.getKey();
            String tags = b2c.getValue().stream()
                    .flatMap(e -> Arrays.stream(e.getFilterTags()).distinct())
                    .distinct()
                    .collect(Collectors.joining(","));
            if (tags.length() > 0) {
                sb.append(String.format("create #%s# = gor %s -s %s -f %s %s | sort 1 -c %s | write -c %s;%n",
                        bucket, table.getPath(), table.getSourceColumn(), tags,
                        table.getSecurityContext() != null ? table.getSecurityContext() : "",
                        table.getSourceColumn(),
                        PathUtils.resolve(absBucketDir, PathUtils.getFileName(bucket))));
            }
        }

        // Must add no-op gor command as the create commands can not be run on their own.
        sb.append("gorrow 1,2 | top 0\n");
        return sb.toString();
    }
}

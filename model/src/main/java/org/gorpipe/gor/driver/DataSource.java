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

package org.gorpipe.gor.driver;

import org.gorpipe.exceptions.GorResourceException;
import org.gorpipe.exceptions.GorSystemException;
import org.gorpipe.gor.binsearch.GorIndexType;
import org.gorpipe.gor.driver.meta.DataType;
import org.gorpipe.gor.driver.meta.SourceMetadata;
import org.gorpipe.gor.driver.meta.SourceReference;
import org.gorpipe.gor.driver.meta.SourceType;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.attribute.FileAttribute;
import java.util.stream.Stream;

/**
 * Represents a data source
 * <p>
 * A single instance of a data source should be used by a
 * single thread only and closed after use.
 * <p>
 * Created by villi on 21/08/15.
 */
public interface DataSource extends AutoCloseable {
    /**
     * Name of source - should contain enough information to resolve the source - e.g. a url.
     */
    String getName();

    /**
     * Returns full path for DataSource
     */
    default String getFullPath() {
        return getName();
    }

    /**
     * Returns path used to validate the access.
     * @throws  GorResourceException if the path can not be created (
     * for example if there is no access).
     */
    default String getAccessValidationPath() {
        if (getSourceReference().isCreatedFromLink()) {
            // For links we just valuate if the user has access for the link.
            return getSourceReference().getOriginalSourceReference().getUrl();
        }
        return getName();
    }

    /**
     * Get type/protocol of source (File, Http, ...)
     */
    SourceType getSourceType();

    /**
     * Get date type - e.g. BAM, GOR .. for files.
     */
    DataType getDataType() throws IOException;

    /**
     * Does this source support writing
     */
    default boolean supportsWriting() {
        return false;
    }

    /**
     * Check for existance of source.
     * Currently, only side effect of always returning true is that
     * automatic fallback to link files wont't work with that source
     */
    boolean exists();

    default void delete() throws IOException {
        throw new GorResourceException("Delete is not implemented", getSourceType().getName());
    }

    default String move(DataSource dest) throws IOException {
        if (getSourceType() != dest.getSourceType()) {
            throw new GorResourceException(String.format("Can not move between different source types (%s to %s)",
                    getFullPath(), dest.getFullPath()), null);
        }
        if (!getFullPath().equals(dest.getFullPath())) {
            copy(dest);
            delete();
        }
        return dest.getFullPath();
    }

    default String copy(DataSource dest) throws IOException {
        throw new GorSystemException(String.format("Copy not available for %s (%s)", getFullPath(), getSourceType()), null);
    }


    default boolean isDirectory() {
        throw new GorResourceException("isDirectory is not implemented", getSourceType().getName());
    }

    /**
     * Creates a new directory.
     * Returns: the directory
     */
    default String createDirectory(FileAttribute<?>... attrs) throws IOException {
        throw new GorResourceException("Create directory is not implemented", getSourceType().getName());
    }

    /**
     * Creates a new directory and its full path if needed.
     * Returns: the directory
     */
    default String createDirectories(FileAttribute<?>... attrs) throws IOException {
        throw new GorResourceException("Create directories is not implemented", getSourceType().getName());
    }

    default Stream<String> list() throws IOException {
        throw new GorResourceException("List directory is not implemented", getSourceType().getName());
    }

    @Override
    void close() throws IOException;

    /**
     * Check if this datasource supports link files, i.e. if we should check for
     * existances of <file name>.link if <file name> is not found.
     * @return true if this datasource supports link files.
     */
    default boolean supportsLinks() {
        return true;
    }

    /**
     * Check if we should force creation of link files when writing out this datasource.
     * @return true if link files should always be created when writing out this datasource.
     */
    default boolean forceLink() {
        return false;
    }

    /**
     * Get the content of a link file, if we decide to create one for this datasource.
     * @return content of a link file pointing to this datasource.
     */
    default String getLinkFileContent() {
        return getFullPath();
    }

    /**
     * The path of the link file.
     * @return path of the link file, null if not set.
     */
    default String getLinkFile() {
        return null;
    }

    /**
     * Index that should be used when writing out this data source.
     * @return the index that should be used
     */
    default GorIndexType useIndex() {
        return GorIndexType.NONE;
    }

    /**
     * Get the source meta data.
     *
     * @return the source meta data.
     */
    SourceMetadata getSourceMetadata() throws IOException;

    /**
     * Get the source reference (url and context).
     *
     * @return the source reference
     */
    SourceReference getSourceReference();
}

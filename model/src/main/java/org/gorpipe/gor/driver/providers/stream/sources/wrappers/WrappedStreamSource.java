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

package org.gorpipe.gor.driver.providers.stream.sources.wrappers;

import org.gorpipe.gor.driver.providers.stream.sources.StreamSource;
import org.gorpipe.gor.driver.providers.stream.sources.StreamSourceMetadata;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Created by villi on 24/08/15.
 */
public class WrappedStreamSource extends WrappedDataSource implements StreamSource {

    public WrappedStreamSource(StreamSource wrapped) {
        super(wrapped);
    }

    @Override
    public StreamSourceMetadata getSourceMetadata() {
        return getWrapped().getSourceMetadata();
    }

    @Override
    public InputStream open()  {
        return getWrapped().open();
    }

    @Override
    public InputStream open(long start)  {
        return getWrapped().open(start);
    }

    @Override
    public InputStream open(long start, long minLength) {
        return getWrapped().open(start, minLength);
    }

    @Override
    public InputStream openClosable() {
        return getWrapped().openClosable();
    }

    @Override
    public OutputStream getOutputStream(long position) {
        return getWrapped().getOutputStream(position);
    }

    @Override
    public OutputStream getOutputStream(boolean append) {
        return getWrapped().getOutputStream(append);
    }

    @Override
    public boolean supportsWriting() {
        return getWrapped().supportsWriting();
    }

    public StreamSource getWrapped() {
        return (StreamSource) super.getWrapped();
    }
}

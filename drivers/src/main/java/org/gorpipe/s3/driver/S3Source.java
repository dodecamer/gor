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

package org.gorpipe.s3.driver;

import com.amazonaws.services.s3.AmazonS3Client;
import com.amazonaws.services.s3.model.*;
import com.google.api.gax.rpc.PermissionDeniedException;
import com.upplication.s3fs.S3FileSystem;

import org.gorpipe.exceptions.GorResourceException;
import org.gorpipe.gor.driver.meta.DataType;
import org.gorpipe.gor.driver.meta.SourceReference;
import org.gorpipe.gor.driver.meta.SourceType;
import org.gorpipe.gor.driver.providers.stream.RequestRange;
import org.gorpipe.gor.driver.providers.stream.sources.StreamSource;

import java.io.*;
import java.net.MalformedURLException;
import java.net.URI;
import java.nio.file.*;
import java.nio.file.attribute.FileAttribute;
import java.util.Properties;
import java.util.stream.Stream;

/**
 * Represents an object in Amazon S3.
 * Created by villi on 22/08/15.
 */
public class S3Source implements StreamSource {
    private final SourceReference sourceReference;
    private final String bucket;
    private final String key;
    private final AmazonS3Client client;
    private S3SourceMetadata meta;

    private Path path;

    /**
     * Create source
     *
     * @param sourceReference contains S3 url of the form s3://bucket/objectpath
     */
    public S3Source(AmazonS3Client client, SourceReference sourceReference) throws MalformedURLException {
        this(client, sourceReference, S3Url.parse(sourceReference.getUrl()));
    }

    S3Source(AmazonS3Client client, SourceReference sourceReference, S3Url url) {
        this.client = client;
        this.sourceReference = sourceReference;
        this.bucket = url.getBucket();
        this.key = url.getPath();
    }

    @Override
    public InputStream open() throws IOException {
        return open(null);
    }

    @Override
    public InputStream open(long start) throws IOException {
        return open(RequestRange.fromFirstLength(start, getSourceMetadata().getLength()));
    }

    @Override
    public InputStream open(long start, long minLength) throws IOException {
        return open(RequestRange.fromFirstLength(start, minLength));
    }

    @Override
    public OutputStream getOutputStream(boolean append) throws IOException {
        if(append) throw new GorResourceException("S3 write not appendable",bucket+"/"+key);
        return new S3MultiPartOutputStream(client, bucket, key);
    }

    @Override
    public boolean supportsWriting() {
        return true;
    }

    private InputStream open(RequestRange range) throws IOException {
        GetObjectRequest req = new GetObjectRequest(bucket, key);
        if (range != null) {
            range = range.limitTo(getSourceMetadata().getLength());
            if (range.isEmpty()) return new ByteArrayInputStream(new byte[0]);
            req.setRange(range.getFirst(), range.getLast());
        }
        return openRequest(req);
    }

    private InputStream openRequest(GetObjectRequest request) {
        S3Object object = client.getObject(request);
        return object.getObjectContent();
    }

    @Override
    public String getName() {
        return sourceReference.getUrl();
    }

    @Override
    public S3SourceMetadata getSourceMetadata() throws IOException {
        if (meta == null) {
            ObjectMetadata md = client.getObjectMetadata(bucket, key);
            meta = new S3SourceMetadata(this, md, sourceReference.getChrSubset());
        }
        return meta;
    }

    @Override
    public SourceReference getSourceReference() {
        return sourceReference;
    }

    @Override
    public DataType getDataType() {
        return DataType.fromFileName(key);
    }

    @Override
    public boolean exists() {
        try {
            return Files.exists(getPath());
        } catch (Exception pde) {
            // For backward compatibility.  doesObjectExists needs less permission than Files.exists, but
            // it does not handle folders properly.
            return client.doesObjectExist(bucket, key);
        }
    }

    @Override
    public String createDirectory(FileAttribute<?>... attrs) throws IOException {
        return Files.createDirectory(getPath()).toString();
    }

    @Override
    public boolean isDirectory() {
        return Files.isDirectory(getPath());
    }

    @Override
    public void delete() throws IOException {
        Files.delete(getPath());
    }

    @Override
    public Stream<String> list() throws IOException {
        return Files.list(getPath()).map(Path::toString);
    }

    private Path getPath() {
        if (path == null) {
            S3FileSystem s3fs;
            try {
                s3fs = (S3FileSystem) FileSystems.getFileSystem(URI.create("s3://" + bucket));
            } catch (ProviderNotFoundException | FileSystemNotFoundException e) {
                s3fs = new S3ClientFileSystemProvider().createFileSystem(URI.create("s3://" + bucket), new Properties(), client);
            }
            path = s3fs.getPath("/" + bucket, key);
        }
        return path;
    }

    @Override
    public SourceType getSourceType() {
        return S3SourceType.S3;
    }

    @Override
    public void close() throws IOException {
        // No resources to free
    }

    public AmazonS3Client getClient() {
        return client;
    }
}

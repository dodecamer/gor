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

package org.gorpipe.gor.driver.providers.stream.datatypes.vcf;

import com.google.auto.service.AutoService;
import org.gorpipe.exceptions.GorResourceException;
import org.gorpipe.gor.driver.adapters.OffsetStreamSourceSeekableFile;
import org.gorpipe.gor.driver.adapters.StreamSourceSeekableFile;
import org.gorpipe.gor.driver.meta.DataType;
import org.gorpipe.gor.driver.providers.stream.FileMetaIterator;
import org.gorpipe.gor.driver.providers.stream.StreamSourceFile;
import org.gorpipe.gor.driver.providers.stream.StreamSourceIteratorFactory;
import org.gorpipe.gor.driver.providers.stream.sources.StreamSource;
import org.gorpipe.gor.binsearch.StringIntKey;
import org.gorpipe.gor.model.*;

import java.io.IOException;
import java.io.InputStream;
import java.util.Comparator;

@AutoService(StreamSourceIteratorFactory.class)
public class VcfIteratorFactory implements StreamSourceIteratorFactory {

    @Override
    public GenomicIterator createIterator(StreamSourceFile file) throws IOException {
        boolean compressed = file.getFileSource().getDataType() == DataType.VCFGZ
                || file.getFileSource().getDataType() == DataType.GVCFGZ
                || file.getFileSource().getDataType() == DataType.VCFBGZ;
        if (file.getIndexSource() == null || !file.getIndexSource().exists()) {
            // Use the same iterator for as the non-seekable .vcf.gz files as for .vcf files if ignore order is requested
            if (compressed) {
                return new VcfFileIterator(file, file.getFileSource().getSourceReference().getLookup(), compressed);
            } else {
                try (InputStream instream = file.getFileSource().open()) {

                    ContigDataScheme dataScheme = new VcfContigDataScheme();
                    final int[] info = VcfFile.findVcfGorDataOffset(instream, dataScheme);
                    final ContigDataScheme finalDataScheme;

                    final Comparator<StringIntKey> comparator;
                    if (dataScheme.length() == 0) {
                        // we should fail heere as there are no contigs in the file and we cannot create a lookup
                        // The default assumes stuff and if there is any mismatch we only get a part of the underlying question
                        throw new GorResourceException("No contigs found in file " + file.getFileSource().getPath() +". Contig description is required when there is no index file available.\nYou can gzip the vcf file and use tabix command line tool to index the vcf file.", file.getFileSource().getPath().toString());
                    } else {
                        final boolean addAnyChrToCache = true;
                        finalDataScheme = dataScheme;
                        ChromoLookup lookup = new VcfChromoLookup(dataScheme, addAnyChrToCache);
                        comparator = StringIntKey.customComparator(lookup.getChromoCache());
                        file.getFileSource().getSourceReference().setLookup(lookup);
                    }
                    StreamSourceSeekableFile sssf = new OffsetStreamSourceSeekableFile(file.getFileSource(), info[0]);
                    return new VcfSeekableIterator(sssf, comparator, finalDataScheme);
                }
            }
        } else {
            return new VcfIndexedFileIterator(file);
        }
    }

    @Override
    public GenomicIteratorBase createMetaIterator(StreamSourceFile file) throws IOException {
        var fileIt = new FileMetaIterator();
        fileIt.initMeta(file);
        return fileIt;
    }

    @Override
    public DataType[] getSupportedDataTypes() {
        return new DataType[]{DataType.VCF, DataType.GVCF, DataType.VCFGZ, DataType.VCFBGZ, DataType.GVCFGZ};
    }

    @Override
    public StreamSourceFile resolveFile(StreamSource source)
            throws IOException {
        switch (source.getDataType()) {
            case GVCF:
            case VCF:
                return new VcfFile(source);
            case VCFGZ:
            case VCFBGZ:
            case GVCFGZ:
                return new VcfGzFile(source);
            default:
                throw new RuntimeException("Unsupported data type " + source.getDataType());
        }
    }

}

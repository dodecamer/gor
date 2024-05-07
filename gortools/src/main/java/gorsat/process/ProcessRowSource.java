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

package gorsat.process;

import gorsat.Commands.CommandParseUtilities;
import gorsat.Commands.GenomicRange;
import gorsat.DynIterator;
import htsjdk.samtools.SamInputResource;
import htsjdk.samtools.SamReader;
import htsjdk.samtools.SamReaderFactory;
import htsjdk.samtools.ValidationStringency;
import org.gorpipe.exceptions.GorResourceException;
import org.gorpipe.exceptions.GorSystemException;
import org.gorpipe.gor.driver.providers.stream.datatypes.bam.BamIterator;
import org.gorpipe.gor.model.*;
import org.gorpipe.gor.session.GorSession;
import org.gorpipe.model.gor.Pipes;
import org.gorpipe.model.gor.RowObj;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.gorpipe.gor.util.CommandSubstitutions;

/**
 * Created by sigmar on 12/02/16.
 */
public class ProcessRowSource extends ProcessSource {
    private final StringBuilder errorStr = new StringBuilder();
    List<String> commands;
    private GenomicIterator it;
    boolean nor;
    private ProcessBuilder pb;
    private Process p;
    private Path fileroot = null;
    private final String filter;

    public ProcessRowSource(String cmd, String type, boolean nor, GorSession session, GenomicRange.Range range, String filter, boolean headerLess) {
        this(CommandParseUtilities.quoteSafeSplit(cmd, ' '), type, nor, session, range, filter, Pipes.rowsToProcessBuffer(), headerLess);
    }

    public ProcessRowSource(String cmd, String type, boolean nor, GorSession session, GenomicRange.Range range, String filter, int bs, boolean headerLess) {
        this(CommandParseUtilities.quoteSafeSplit(cmd, ' '), type, nor, session, range, filter, bs, headerLess);
    }

    public static String checkNested(String cmd, GorSession session, StringBuilder errorStr) {
        String ncmd;
        if( cmd.startsWith("<(") ) {
            String tmpdir = System.getProperty("java.io.tmpdir");
            if( tmpdir == null || tmpdir.length() == 0 ) tmpdir = "/tmp";
            Path tmpath = Paths.get(tmpdir);
            String scmd = cmd.substring(2,cmd.length()-1);
            Path fifopath = tmpath.resolve( Integer.toString(Math.abs(scmd.hashCode())) );
            String pipename = fifopath.toAbsolutePath().toString();
            try {
                if( !Files.exists(fifopath) ) {
                    ProcessBuilder mkfifo = new ProcessBuilder("mkfifo", pipename);
                    Process p = mkfifo.start();
                    p.waitFor();
                }
                Thread t = new Thread(() -> {
                    try (OutputStream os = Files.newOutputStream(fifopath);
                         DynIterator.DynamicRowSource drs = new DynIterator.DynamicRowSource(scmd, session.getGorContext(), false)) {
                        os.write( drs.getHeader().getBytes() );
                        os.write( '\n' );
                        while( drs.hasNext() ) {
                            String rowstr = drs.next().toString();
                            os.write( rowstr.getBytes() );
                            os.write('\n');
                        }
                    } catch (IOException e) {
                        errorStr.append(e.getMessage());
                    } finally {
                        try {
                            Files.delete( fifopath );
                        } catch (IOException e) {
                            // Ignore
                        }
                    }
                });
                t.start();
            } catch (InterruptedException | IOException e) {
                throw new GorSystemException("Failed starting fifo thread",e);
            }
            ncmd = pipename;
        } else {
            boolean quotas = cmd.startsWith("'") || cmd.startsWith("\"");
            ncmd = quotas ? cmd.substring(1, cmd.length() - 1) : cmd;
            if (quotas) ncmd = ncmd.replace("\\t", "\t").replace("\\n", "\n");
        }
        return ncmd;
    }

    private ProcessRowSource(String[] cmds, String type, boolean nor, GorSession session, GenomicRange.Range range, String fltr, int bs, boolean headerLess) {
        this.nor = nor;
        this.setBufferSize(bs);
        this.filter = fltr;
        commands = new ArrayList<>();

        if (session != null) {
            String root = session.getProjectContext().getRoot();
            if (root != null && root.length() > 0) {
                int i = root.indexOf(' ');
                if (i == -1) i = root.length();
                fileroot = Paths.get(root.substring(0, i));
            }
        }

        for (String cmd : cmds) {
            String ncmd = checkNested(cmd, session, errorStr);
            commands.add( ncmd );
        }

        boolean bamvcf = type != null && (type.equals("bam") || type.equals("sam") || type.equals("cram") || type.equals("vcf"));
        List<String> headercommands =
                bamvcf ? CommandSubstitutions.cmdSetFilterAndSeek(commands, null, 0, -1, null)
                       : CommandSubstitutions.cmdSetFilterAndSeek(commands, range.chromosome(), range.start(), range.stop(), filter);

        try {
            List<String> rcmd = headercommands.stream().filter(p -> p.length() > 0).collect(Collectors.toList());
            pb = new ProcessBuilder(rcmd);
            if (fileroot != null) pb.directory(fileroot.toFile());
            p = pb.start();
            Thread errorThread = new Thread(() -> {
                try {
                    InputStream es = p.getErrorStream();
                    BufferedReader br = new BufferedReader(new InputStreamReader(es));
                    String line = br.readLine();
                    while (line != null) {
                        errorStr.append(line).append("\n");
                        line = br.readLine();
                    }
                    br.close();
                } catch (IOException e) {
                    // don't care throw new RuntimeException("", e);
                }
            });
            errorThread.start();
            InputStream is = p.getInputStream();

            if (type == null || type.equalsIgnoreCase("gor")) {
                it = gorIterator( is, headercommands, type, headerLess );
            } else if (type.equalsIgnoreCase("vcf")) {
                it = vcfIterator( is );
                if( range.chromosome() != null ) it.seek(range.chromosome(), range.start(), range.stop());
            } else if (type.equalsIgnoreCase("bam") || type.equalsIgnoreCase("sam") || type.equalsIgnoreCase("cram")) {
                it = bamIterator( is );
            }
            if( range.chromosome() != null ) it.seek(range.chromosome(), range.start(), range.stop());
            String header = it.getHeader();
            setHeader(header);
        } catch (IOException e) {
            throw new GorResourceException("unable to get header from process " + commands.get(0), "", e);
        }
    }

    private GenomicIterator gorIterator( InputStream is, List<String> headercommands, String type, boolean headerLess ) throws IOException {
        BufferedReader br = new BufferedReader(new InputStreamReader(is));
        String header = null;
        if (!headerLess) {
            header = br.readLine();
            if (header == null) {
                throw new GorSystemException("Running external process: " + String.join(" ", headercommands) + " with error: " + errorStr, null);
            }
        }
        var it = new GenomicIteratorBase() {
            BufferedReader reader = br;
            String next = readLine();

            private String readLine() throws IOException {
                String line = reader.readLine();
                if (line == null) return null;
                return nor ? "chrN\t0\t" + line : line;
            }

            @Override
            public boolean hasNext() {
                return next != null;
            }

            @Override
            public Row next() {
                Row row = RowObj.StoR(next);
                try {
                    next = readLine();
                } catch (IOException e) {
                    throw new GorSystemException("Error reading next line from external process", e);
                }

                return row;
            }

            @Override
            public boolean seek(String seekChr, int seekPos) {
                InputStream is = setRange(seekChr, seekPos, -1);
                reader = new BufferedReader(new InputStreamReader(is));
                try {
                    if (type != null) readLine();
                    next = readLine();
                } catch (IOException e) {
                    throw new GorSystemException("Error reading next line from external process after seek", e);
                }
                return true;
            }

            @Override
            public void close() {
                try {
                    reader.close();
                    p.destroy();
                } catch (IOException e) {
                    // don't care if external process stdout stream fails closing, could already have been closed by the process itself
                }
            }
        };
        if (headerLess) {
            var spl = it.next.split("\t");
            var hdr = IntStream.rangeClosed(1,spl.length).mapToObj(i -> "col"+i).collect(Collectors.joining("\t"));
            it.setHeader(hdr);
        } else {
            if (nor) it.setHeader("ChromNOR\tPosNOR\t" + header.replace(" ", "_").replace(":", ""));
            else it.setHeader(header);
        }
        return it;
    }

    private GenomicIterator vcfIterator( InputStream is ) {
        BufferedReader br = new BufferedReader(new InputStreamReader(is));
        ChromoLookup lookup = createChromoLookup();
        GenomicIterator vcfit;
        try {
            vcfit = new VcfGzGenomicIterator(lookup, "filename", br) {
                @Override
                public boolean seek(String seekChr, int seekPos) {
                    return seek(seekChr, seekPos, lookup.chrToLen(seekChr));
                }

                @Override
                public boolean seek(String seekChr, int seekPos, int endPos) {
                    try {
                        reader.close();
                        if (seekChr != null && this.chrNameSystem != VcfGzGenomicIterator.ChrNameSystem.WITH_CHR_PREFIX)
                            seekChr = seekChr.substring(3);
                        InputStream is1 = setRange(seekChr, seekPos, endPos == 0 ? 1 : endPos);
                        reader = new BufferedReader(new InputStreamReader(is1));
                        next = reader.readLine();
                        while (next != null && next.startsWith("##")) {
                            next = reader.readLine();
                        }
                        while (next != null && !next.startsWith("#")) {
                            next = reader.readLine();
                        }
                        while (next != null && next.startsWith("#")) {
                            next = reader.readLine();
                        }
                    } catch (IOException e) {
                                throw new GorSystemException("Error reading next line from external process providing vcf stream", e);
                    }
                    return true;
                }

                @Override
                public void close() {
                    super.close();
                }
            };
        } catch (Exception e) {
            p.destroy();
            int exitValue = 0;
            try {
                boolean didStop = p.waitFor(1, TimeUnit.SECONDS);
                if( !didStop ) {
                    p.destroyForcibly();
                    exitValue = p.waitFor();
                } else exitValue = p.exitValue();
            } catch (InterruptedException ie) {
                errorStr.append( ie.getMessage() );
            }
            throw new GorSystemException("Error initializing vcf reader. Exit value from process: " + exitValue + ". Error from process: " + errorStr, e);
        }
        return vcfit;

    }

    private GenomicIterator bamIterator( InputStream is ) {
        ChromoLookup lookup = createChromoLookup();
        SamReaderFactory srf = SamReaderFactory.makeDefault().validationStringency(ValidationStringency.SILENT);
        SamInputResource sir = SamInputResource.of(is);
        SamReader samreader = srf.open(sir);
        BamIterator bamit = new BamIterator() {
            @Override
            public boolean seek(String chr, int pos) {
                return super.seek(chr, pos);
            }

            @Override
            public boolean seek(String chr, int pos, int end) {
                int chrId = lookup.chrToId(chr); // Mark that a single chromosome seek
                if (chrnamesystem == 1) { // BAM data on hg chromsome names, use the hg name for the chromsome for the seek
                    chr = ChromoCache.getHgName(chrId);
                } else if (chrnamesystem == 2) {
                    chr = ChromoCache.getStdChrName(chrId);
                }

                try {
                    this.reader.close();
                } catch (IOException e) {
                    // don't care if external process stream has already been closed
                }
                InputStream nis = setRange(chr, pos, end);
                SamInputResource sir = SamInputResource.of(nis);
                this.reader = srf.open(sir);
                this.pos = pos;

                return true;
            }

            @Override
            public boolean hasNext() {
                initIterator();
                boolean hasNext = it.hasNext();
                while (hasNext && (record = it.next()) != null && (record.getReadUnmappedFlag() || "*".equals(record.getCigarString()) || record.getStart() < pos)) {
                    hasNext = it.hasNext();
                }
                if (!hasNext) {
                    if (hgSeekIndex >= 0) { // Is seeking through differently ordered data
                        while (++hgSeekIndex < ChrDataScheme.ChrLexico.getOrder2id().length) {
                            String name = getChromName();
                            if (samFileHeader.getSequenceIndex(name) > -1) {
                                createIterator(name, 0);
                                return hasNext();
                            }
                        }
                    }
                }
                return hasNext;
            }

            @Override
            public void createIterator(String chr, int pos) {
                if( it == null ) it = reader.iterator();
            }
        };
        bamit.init(lookup, samreader, false);
        bamit.chrnamesystem = 0;
        return bamit;
    }

    @Override
    public boolean hasNext() {
        return it.hasNext();
    }

    @Override
    public Row next() {
        return it.next();
    }


    @Override
    public InputStream setRange(String seekChr, int startPos, int endPos) {
        try {
            List<String> seekcmd = CommandSubstitutions.cmdSetFilterAndSeek(commands, seekChr, startPos, endPos, filter);
            if (it != null) it.close();

            if (p != null && p.isAlive()) {
                p.destroy();
            }
            List<String> cmdlist = seekcmd.stream().filter(p -> p.length() > 0).collect(Collectors.toList());
            pb = new ProcessBuilder(cmdlist);
            if (fileroot != null) pb.directory(fileroot.toFile());
            p = pb.start();

            Thread errorThread = new Thread(() -> {
                try {
                    InputStream es = p.getErrorStream();
                    BufferedReader br = new BufferedReader(new InputStreamReader(es));
                    String line = br.readLine();
                    while (line != null) {
                        errorStr.append(line).append("\n");
                        line = br.readLine();
                    }
                    br.close();
                } catch (IOException e) {
                    // don't care throw new RuntimeException("Error reading stderr from external process", e);
                }
            });
            errorThread.start();

            return p.getInputStream();
        } catch (IOException e) {
            throw new GorSystemException("Unable to read line from external process in seek: " + commands, e);
        }
    }

    @Override
    public boolean seek(String seekChr, int seekPos) {
        return it.seek(seekChr, seekPos);
    }

    @Override
    public void close() {
        if (it != null) it.close();
        if (p != null && p.isAlive()) {
            p.destroy();
        }
    }

    @Override
    public boolean isBuffered() {
        return true;
    }

    public static ChromoLookup createChromoLookup() {
        final ChromoCache lookupCache = new ChromoCache();
        final boolean addAnyChrToCache = true;
        final ChrDataScheme dataOutputScheme = ChrDataScheme.ChrLexico;
        return new ChromoLookup() {
            @Override
            public final String idToName(int id) {
                return lookupCache.toName(dataOutputScheme, id);
            }

            @Override
            public final int chrToId(String chr) {
                return lookupCache.toIdOrUnknown(chr, addAnyChrToCache);
            }

            @Override
            public final int chrToLen(String chr) {
                return lookupCache.toLen(chr);
            }

            @Override
            public final int chrToId(CharSequence str, int strlen) {
                return lookupCache.toIdOrUnknown(str, strlen, addAnyChrToCache);
            }

            @Override
            public final int prefixedChrToId(byte[] buf, int offset) {
                return lookupCache.prefixedChrToIdOrUnknown(buf, offset, addAnyChrToCache);
            }

            @Override
            public final int prefixedChrToId(byte[] buf, int offset, int buflen) {
                return lookupCache.prefixedChrToIdOrUnknown(buf, offset, buflen, addAnyChrToCache);
            }

            @Override
            public ChromoCache getChromoCache() {
                return lookupCache;
            }
        };
    }
}

package org.gorpipe.gor.table.files;

import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.RandomStringUtils;
import org.gorpipe.exceptions.GorSystemException;
import org.gorpipe.gor.model.FileReader;
import org.gorpipe.gor.model.Row;
import org.gorpipe.gor.model.RowBase;
import org.gorpipe.gor.table.BaseTable;
import org.gorpipe.gor.table.GorPipeUtils;
import org.gorpipe.gor.table.TableHeader;
import org.gorpipe.gor.table.TableLog;
import org.gorpipe.gor.table.dictionary.DictionaryEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Table class representing file (gor/gorz/nor/tsv etc)
 *
 * The internal data is stored in temp files.
 *
 */
public abstract class FileTable<T extends Row> extends BaseTable<T> {

    private static final Logger log = LoggerFactory.getLogger(FileTable.class);

    protected String tempOutFilePath;

    public FileTable(Builder builder) {
        super(builder);
        init();
    }

    public FileTable(URI uri, FileReader inputFileReader) {
        super(uri, inputFileReader);
        init();
    }

    private void init() {
        this.header = new TableHeader();
        if (fileReader.exists(getPath().toString())) {
            validateFile(getPath().toString());
        }
        reload();
    }

    @Override
    public Stream<String> getLines() {
        try {
            BufferedReader reader = fileReader.getReader(getMainFile());

            Stream<String> stream = reader.lines();
            stream.onClose(() -> {
                try {
                    reader.close();
                } catch (IOException e) {
                    // Ignore, best effort.
                }
            });
            return stream;
        } catch (IOException e) {
            throw new GorSystemException("Error getting file reader", e);
        }
    }

    @Override
    public void insert(Collection<T> lines) {
        Path tempInputFile;
        try {
            tempInputFile = createInputTempFile(lines);
        } catch (IOException e) {
            throw new GorSystemException("Could not create temp file for inserting data", e);
        }
        insertFiles(tempInputFile.toString());

        logAfter(TableLog.LogAction.INSERT, "", lines.stream().map(l -> l.otherCols()).toArray(String[]::new));
    }

    @Override
    public void insert(String... lines) {
        List<T> entries = lineStringsToEntries(lines);
        insert(entries);
    }

    @Override
    public void insertEntries(Collection<DictionaryEntry> entries) {
        insertFiles(entries.stream().map(DictionaryEntry::getContentReal).toArray(String[]::new));
    }

    protected void insertFiles(String... gorFiles) {
        // Validate the new file.
        if (isValidateFiles()) {
            for (String gorFile : gorFiles) {
                validateFile(gorFile);
            }
        }
        String outFile = getNewTempFileName();
        String gorPipeCommand = createInsertTempFileCommand(getMainFile(), outFile, gorFiles);
        GorPipeUtils.executeGorPipeForSideEffects(gorPipeCommand, 1, getProjectPath(), fileReader.getSecurityContext());
        tempOutFilePath = outFile;
        // Use folder for the transaction.  Then queries can be run on the new file, within the transl
    }

    @Override
    public void delete(Collection<T> lines) {
        createDeleteTempFile(lines.stream().map(l -> l.getAllCols().toString()).toArray(String[]::new));
        throw new GorSystemException("DeleteEntries not supported yet for FileTable", null);
    }

    @Override
    public void delete(String... lines) {
        createDeleteTempFile(lines);
    }

    @Override
    public void deleteEntries(Collection<DictionaryEntry> entries) {
        throw new GorSystemException("DeleteEntries not supported yet for FileTable", null);
    }

    protected void createDeleteTempFile(String... lines) {
        String localTempPath = getNewTempFileName();

        String[] strippedLines = Arrays.stream(lines).map(line -> line.endsWith("\n") ? line.substring(0, line.length() - 1) : line).toArray(String[]::new);
        try (OutputStream os = fileReader.getOutputStream(localTempPath)) {
            try (Stream<String> stream = getLines()) {
                stream.filter(l -> includeLine(l, strippedLines)).forEach(l -> writeOutLine(os, l));
            }
        } catch (IOException e) {
            throw new GorSystemException(e);
        }
        tempOutFilePath = localTempPath;
    }

    private void writeOutLine(OutputStream os, String line) {
        try {
            os.write(line.getBytes(StandardCharsets.UTF_8));
            os.write('\n');
        } catch (IOException e) {
            throw new GorSystemException(e);
        }

    }

    private boolean includeLine(String line, String[] skipLines) {
        for (String skipLine : skipLines) {
            if (line.equals(skipLine)) {
                return false;
            }
        }
        return true;
    }

    protected List<T> lineStringsToEntries(String[] lines) {
        List<T> entries = new ArrayList<>();
        for (String line : lines) {
            if (line.trim().isEmpty() || line.startsWith("#")) {
                continue;
            }
            entries.add(createRow(line));
        }
        return entries;
    }

    protected T createRow(String line) {
        return (T)new RowBase(line.endsWith("\n") ? line.substring(0, line.length() - 1) : line);
    }

    protected abstract String getInputTempFileEnding();

    protected abstract String getGorCommand();

    @Override
    public void saveTempMainFile() {
        // Move our temp file to the standard temp file and clean up.
        // or if these are links update the link file to point to the new temp file.
        // Clean up (remove old files and temp files)  s
        log.debug(String.format("Saving temp file (%s)to temp main file (%s) ",  tempOutFilePath, getTempMainFileName()));
        try {
            if (tempOutFilePath != null && getFileReader().exists(tempOutFilePath.toString())) {
                updateFromTempFile(tempOutFilePath.toString(), getTempMainFileName());
                tempOutFilePath = null;
                getFileReader().deleteDirectory(getTransactionFolderPath().toString());
            } else if (!getFileReader().exists(getPath().toString())) {
                writeToFile(Path.of(getTempMainFileName()), new ArrayList<>());
            }
        } catch (IOException e) {
            throw new GorSystemException("Could not save table", e);
        }
    }

    @Override
    public void initialize() {
        super.initialize();
    }

    protected Path getTransactionFolderPath() {
        return Path.of(getFolderPath().toString(), "transactions");
    }
        
    protected void writeToFile(Path filePath, Collection<T> lines) throws IOException {
        try (OutputStream os = fileReader.getOutputStream(filePath.toString())) {
            os.write('#');
            os.write(Stream.of(getColumns()).collect(Collectors.joining("\t")).getBytes(StandardCharsets.UTF_8));
            os.write('\n');
            for (Row r : lines) {
                writeRowToStream(r, os);
                os.write('\n');
            }
        }
    }

    protected void writeRowToStream(Row r, OutputStream os) throws IOException {
        r.writeRowToStream(os);
    }

    protected abstract String createInsertTempFileCommand(String mainFile, String outFile, String... insertFiles);

    protected String getPostProcessing() {
        String insertPostProcessing = getHeader().getProperty(TableHeader.HEADER_SELECT_TRANSFORM_KEY, "");
        if (!insertPostProcessing.isEmpty() && !insertPostProcessing.trim().startsWith("|")) {
            insertPostProcessing = "| " + insertPostProcessing;
        }
        return insertPostProcessing;
    }

    private Path createInputTempFile(Collection<T> lines) throws IOException {
        String randomString = RandomStringUtils.random(8, true, true);
        Path tempFilePath = getTransactionFolderPath().resolve("insert_temp_" + randomString + getInputTempFileEnding());

        fileReader.createDirectories(tempFilePath.getParent().toString());
        writeToFile(tempFilePath, lines);

        return tempFilePath;
    }

    private String getNewTempFileName() {
        String randomString = RandomStringUtils.random(8, true, true);
        return getTransactionFolderPath().resolve(
                String.format("result_temp_%s.%s", randomString, FilenameUtils.getExtension(getPath().toString()))).toString();
    }

    protected String getMainFile() {
        String mainFile;
        if (tempOutFilePath == null) {
            mainFile = getPath().toString();
        } else {
            mainFile = tempOutFilePath;
        }
        return mainFile;
    }
}
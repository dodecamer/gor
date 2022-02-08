package org.gorpipe.gor.model;


import org.apache.commons.lang3.StringUtils;
import org.gorpipe.exceptions.GorResourceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Helper class to implement meta files.
 * - Contains common properties.
 * - Helper methods to read and write meta files.
 */
public class BaseMeta {
    private static final Logger log = LoggerFactory.getLogger(BaseMeta.class);

    public static final String NO_SERIAL = "0";

    // Basic properties
    public static final String HEADER_FILE_FORMAT_KEY = "FILE_FORMAT";
    public static final String HEADER_SERIAL_KEY = "SERIAL";
    public static final String HEADER_CREATED_KEY = "CREATED";
    public static final String HEADER_LINE_COUNT_KEY = "LINE_COUNT";
    public static final String HEADER_MD5_KEY = "MD5";
    public static final String HEADER_TAGS_KEY = "TAGS";
    public static final String HEADER_COLUMNS_KEY = "COLUMNS";

    protected HashMap<String, String> headerProps;
    private String[] fileHeader;                     // Columns of the table it self.
    protected boolean saveHeaderLine = false;

    /**
     *
     */
    public BaseMeta() {
        this.headerProps = new HashMap<>();
        clear();
    }

    /**
     * Get header property.
     *
     * @param key name of the property.
     * @return the header property identifed with [key], null if the key does not exists.
     */
    public String getProperty(String key) {
        return headerProps.get(key);
    }

    /**
     * Get header property.
     *
     * @param key name of the property.
     * @return the header property identifed with [key]
     */
    public String getProperty(String key, String defValue) {
        return headerProps.containsKey(key) ? headerProps.get(key) : defValue;
    }

    /**
     * Set header property.
     *
     * @param key   name of the property.
     * @param value new value of the property.
     */
    public void setProperty(String key, String value) {
        if (!headerProps.containsKey(key) || !headerProps.get(key).equals(value)) {
            headerProps.put(key, value);
        }
    }

    /**
     * Returns <code>true</code> if this header contains the property.
     *
     * @param key The property key.
     * @return <code>true</code> if this header contains the property.
     */
    public boolean containsProperty(String key) {
        return headerProps.containsKey(key);
    }

    /**
     * Clear the header info.
     */
    public void clear() {
        this.headerProps.clear();
        setProperty(HEADER_SERIAL_KEY, NO_SERIAL);
    }

    /**
     * Check if proper header.
     *
     * @return true if the header is a proper header otherwise false.  Header is proper if it has defined contentColumns and they are not dummy contentColumns.
     */
    public boolean isProper() {
        String[] contentColumns = getColumns();
        return contentColumns != null && contentColumns.length > 2 && contentColumns[2].equalsIgnoreCase("col3");
    }

    public void setColumns(String[] columns) {
        String[] contentColumns = Stream.of(columns).filter(Objects::nonNull).map(String::trim).filter(h -> h.length() > 0).toArray(size -> new String[size]);
        setProperty(HEADER_COLUMNS_KEY, String.join(",", contentColumns));
    }

    public String[] getColumns() {
        return containsProperty(HEADER_COLUMNS_KEY) ? getProperty(HEADER_COLUMNS_KEY).split(",") : new String[]{};
    }

    public void setMd5(String md5) {
        setProperty(HEADER_MD5_KEY, md5);
    }

    public String getMd5() {
        return getProperty(HEADER_MD5_KEY);
    }

    public void setTags(String tags) {
        setProperty(HEADER_TAGS_KEY, tags);
    }

    public String getTags() {
        return getProperty(HEADER_TAGS_KEY);
    }

    public int getLineCount() {
        if (headerProps.containsKey(HEADER_LINE_COUNT_KEY)) {
            return Integer.parseInt(headerProps.get(HEADER_LINE_COUNT_KEY));
        }
        return -1;
    }

    public String[] getFileHeader() {
        return fileHeader;
    }

    public void setFileHeader(String[] fileHeader) {
        this.fileHeader = fileHeader;
    }

    /**
     * Parse header line.
     *
     * @param line line to parse.
     * @return true if the line was a header line.
     */
    public boolean parseLine(String line) {
        if (line == null) {
            return false;
        } else if (line.startsWith("##")) {
            parseMetaLine(line);
            return true;
        } else if (line.startsWith("#")) {
            parseHeaderLine(line);
            return true;
        } else {
            return false;
        }
    }

    protected void parseMetaLine(String line) {
        String[] lineSplit = line.split("[=:]", 2);
        String propName = StringUtils.strip(lineSplit[0], "\t\n #");
        if (propName.equals(HEADER_COLUMNS_KEY)) {
            setColumns(lineSplit[1].trim().split("[\t,]", -1));
        } else {
            setProperty(propName, lineSplit[1].trim());
        }
    }

    protected void parseHeaderLine(String line) {
        if (containsProperty(HEADER_COLUMNS_KEY)) {
            return;
        }
        String columnsString = StringUtils.strip(line, "\n #");
        if (columnsString.length() > 0) {
            setFileHeader(columnsString.split("[\t,]", -1));
            setColumns(fileHeader);
        }
    }

    /**
     * Check if the given line is a header line.
     *
     * @param line line to check.
     * @return true if the given line is a table header line.
     */
    public static boolean isHeaderLine(String line) {
        return line != null && line.startsWith("#");
    }

    /**
     * Format the header for outputting into a file.
     *
     * @return the header formatted for outputting into a table file.
     */
    public String formatHeader() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : this.headerProps.entrySet()) {
            sb.append(String.format("## %s = %s%n", entry.getKey(), entry.getValue()));
        }

        if (fileHeader != null && saveHeaderLine) {
            sb.append(String.format("#%s%n", String.join("\t", this.fileHeader)));
        }

        return sb.toString();
    }

    public void loadAndMergeMeta(Path metaPath) {
        if (metaPath == null || !Files.exists(metaPath)) {
            return;
        }

        try {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(metaPath.toString())))) {
                String line;
                while ((line = br.readLine()) != null) {
                    line = line.trim();
                    if (line.length() > 0) {
                        if (isHeaderLine(line)) {
                            parseLine(line);
                        } else {
                            // Done reading the header.
                            break;
                        }
                    }
                }
            }
        } catch (IOException ex) {
            throw new GorResourceException("Error Initializing Query. Can not read file " + metaPath, metaPath.toString(), ex);
        }
    }
}
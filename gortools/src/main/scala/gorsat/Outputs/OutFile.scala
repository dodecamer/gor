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

package gorsat.Outputs

import gorsat.Analysis.OutputOptions
import gorsat.Commands.{Output, RowHeader}
import gorsat.parquet.GorParquetFileOut
import htsjdk.samtools.util.{BlockCompressedInputStream, BlockCompressedOutputStream, Md5CalculatingOutputStream}
import htsjdk.tribble.index.tabix.{TabixFormat, TabixIndexCreator}
import htsjdk.tribble.readers.{AsciiLineReader, AsciiLineReaderIterator}
import htsjdk.tribble.util.LittleEndianOutputStream
import htsjdk.variant.vcf.VCFCodec
import org.gorpipe.exceptions.GorResourceException
import org.gorpipe.gor.binsearch.GorIndexType
import org.gorpipe.gor.model.{FileReader, Row}
import org.gorpipe.gor.util.DataUtil

import java.io._
import java.nio.file.Path
import java.util.zip.Deflater

/**
  * @param name Name of the file to be written.
  * @param header Header of the incoming source.
  * @param skipHeader Whether the header should be written or not.
  * @param append Whether we should write the output to the beginning or end of the file.
  * @param md5 Whether the md5 sum of the file's content should be written to a side file or not.
  */
class OutFile(name: String, fileReader: FileReader, header: String, skipHeader: Boolean = false, append: Boolean = false, md5File: Boolean, md5: Boolean, idx: GorIndexType, compressionLevel: Int) extends Output {
  val finalFileOutputStream: OutputStream = fileReader.getOutputStream(name, append)
  private val interceptingFileOutputStream: OutputStream =
    if (md5) {
      new Md5CalculatingOutputStream(finalFileOutputStream, if(md5File) new File(name + ".md5") else null)
    } else {
      finalFileOutputStream
    }
  private val gzippedOutputStream: OutputStream =
    if (name.toLowerCase.endsWith(".gz") || name.toLowerCase.endsWith(".bgz")) {
      val p : Path = null
      new BlockCompressedOutputStream(interceptingFileOutputStream, p, compressionLevel)
    } else {
      interceptingFileOutputStream
    }
  val out: Writer =
    new java.io.OutputStreamWriter(new BufferedOutputStream(gzippedOutputStream, 1024 * 128))

  override def getName: String = name

  def setup(): Unit = {
    if (header != null & !skipHeader) {
      if (!header.startsWith("#")) {
        out.write("#")
      }
      out.write(header + "\n")
    }
  }

  def process(r: Row): Unit = {
    out.write(r.toString)
    out.write('\n')
  }

  def finish(): Unit = {
    out.flush()
    out.close()
    getMeta.setMd5(interceptingFileOutputStream match {
      case stream: Md5CalculatingOutputStream =>
        stream.md5()
      case _ => null
    })

    if(idx == GorIndexType.TABIX) {
      val bcis = new BlockCompressedInputStream(fileReader.getInputStream(name))

      val gpi = fileReader.getOutputStream(name+".tbi")
      val tbi = new TabixIndexCreator(TabixFormat.VCF)

      val dummyPath: Path = null
      val outputStream = new LittleEndianOutputStream(new BlockCompressedOutputStream(gpi, dummyPath))
      val codec = new VCFCodec
      val lineReader = AsciiLineReader.from(bcis)
      val iterator = new AsciiLineReaderIterator(lineReader)
      codec.readActualHeader(iterator)
      while (iterator.hasNext) {
        val position = iterator.getPosition
        val currentContext = codec.decode(iterator.next)
        tbi.addFeature(currentContext, position)
      }
      val index = tbi.finalizeIndex(iterator.getPosition)
      iterator.close()
      index.write(outputStream)
      outputStream.close()
    }
  }
}

object OutFile {

  def vcfHeader(prefix: String, header: String): String = {
    prefix + "\n" + (if(header.startsWith("#")) header else "#"+header)
  }

  def driver(name: String, fileReader: FileReader, inheader: RowHeader, skipHeader: Boolean, options: OutputOptions): Output = {
    driver(name, fileReader, inheader.toString, skipHeader, options, inheader.columnTypes)
  }

  def driver(name: String, fileReader: FileReader, inheader: String, skipHeader: Boolean, options: OutputOptions): Output = {
    driver(name, fileReader, inheader, skipHeader, options, null)
  }

  def driver(name: String, fileReader: FileReader, inheader: String, skipHeader: Boolean, options: OutputOptions, schema: Array[String]): Output = {
    val nameUpper = name.toUpperCase
    val isVCF = DataUtil.isAnyVcf(name)
    var append = skipHeader
    val header = if(options.prefix.isDefined) {
      val pref = options.prefix.get
      if(isVCF) {
        vcfHeader(pref, inheader)
      } else {
        pref + inheader
      }
    } else if(options.prefixFile.isEmpty && isVCF) {
      vcfHeader("##fileformat=VCFv4.2", inheader)
    } else {
      append = append || {
        options.prefixFile match {
          case Some(prefixName) =>
            writePrefix(fileReader, prefixName, name)
            true
          case None => false
        }
      }
      inheader
    }

    try {
      val out = if (DataUtil.isGorz(nameUpper) || DataUtil.isNorz(nameUpper)) {
        new GORzip(name, fileReader, header, skipHeader, append, options, schema)
      } else if (DataUtil.isTsv(nameUpper) || DataUtil.isNor(nameUpper)) {
        new NorFileOut(name, fileReader, header, skipHeader, append, options.md5, options.md5File)
      } else if (DataUtil.isParquet(nameUpper)) {
        new GorParquetFileOut(name, header, options.nor)
      } else if (options.nor) {
        new CmdFileOut(name, fileReader, header, skipHeader, append)
      } else {
        new OutFile(name, fileReader, header, skipHeader, append, options.md5File, options.md5, options.idx, options.compressionLevel)
      }
      if (options.dictTags != null && options.dictTags.length > 0) {
        out.getMeta.setTags(options.dictTags.mkString(","))
      }
      out
    } catch {
      case e: FileNotFoundException => throw new GorResourceException(s"Can't write to file", name, e)
    }
  }

  def apply(name: String, fileReader: FileReader, header: String, skipHeader: Boolean, columnCompress: Boolean, nor: Boolean, md5: Boolean, md5File: Boolean, infer: Boolean = false, idx: GorIndexType, prefixFile: Option[String] = None, compressionLevel: Int = Deflater.BEST_SPEED): Output =
    driver(name, fileReader, header, skipHeader, OutputOptions(remove = false, columnCompress = columnCompress, md5 = md5, md5File = md5File, nor = nor, idx, null, null, None, prefixFile, compressionLevel, infer = infer))

  def apply(name: String, fileReader: FileReader, header: String, skipHeader: Boolean, nor: Boolean, md5: Boolean): Output = driver(name, fileReader, header, skipHeader, OutputOptions(nor = nor, md5 = md5, md5File = md5))

  def apply(name: String, fileReader: FileReader, header: String, skipHeader: Boolean, nor: Boolean, md5: Boolean, command: String): Output = driver(name, fileReader, header, skipHeader, OutputOptions(nor = nor, md5 = md5, md5File = md5, command = command))

  def apply(name: String, fileReader: FileReader, header: String, skipHeader: Boolean, nor: Boolean): Output = driver(name, fileReader, header, skipHeader, OutputOptions(nor = nor))

  def apply(name: String, fileReader: FileReader, header: String, skipHeader: Boolean): Output = driver(name, fileReader, header, skipHeader, OutputOptions())

  def apply(name: String, fileReader: FileReader, header: String): Output = driver(name, fileReader, header, skipHeader = false, OutputOptions())

  def apply(name: String, fileReader: FileReader): Output = driver(name, fileReader, null, skipHeader = false, OutputOptions(), null)

  private def writePrefix(fileReader: FileReader, prefixFileName: String, fileName: String): Unit = {
    val is = fileReader.getInputStream(prefixFileName)
    val os = fileReader.getOutputStream(fileName)
    val buffer = new Array[Byte](1024) //Reasonable buffer size - there is usually not that much in the file.
    var addNewLine = false
    var read = is.read(buffer)
    while (read > 0) {
      os.write(buffer, 0, read)
      addNewLine = buffer(read - 1) != '\n'
      read = is.read(buffer)
    }
    if (addNewLine) os.write('\n')
    is.close()
    os.close()
  }
}

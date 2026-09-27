package avro2s.wire.runtime

import java.nio.charset.StandardCharsets

/** Public JDK conversion paths, with strict validation when replacement could have occurred.
  * String's charset overloads replace malformed input with the charset's default replacement.
  * Absence of that replacement therefore proves that conversion accepted the original input.
  * Literal replacements are checked against the original text; they are never rejected merely
  * because they match the sentinel. No decoder/encoder instance is shared between calls.
  */
private[runtime] object StrictUtf8:
  private val charset = StandardCharsets.UTF_8
  private val decodedReplacement = charset.newDecoder().replacement()
  private val encodedReplacementByte = charset.newEncoder().replacement()(0)
  private val lowBytes = 0x0101010101010101L
  private val highBytes = 0x8080808080808080L
  private val replacementWord = (encodedReplacementByte & 0xffL) * lowBytes

  def mayHaveDecodingReplacement(value: String): Boolean = value.indexOf(decodedReplacement) >= 0

  def encode(value: String): Array[Byte] =
    val bytes = value.getBytes(charset)
    validateEncodingReplacement(value, bytes)
    bytes

  /** Plain VarHandle reads allow unaligned byte-array offsets. Every load is in bounds.
    * The zero-byte test works in either byte order and only asks whether any byte matches.
    * Until the first non-ASCII output byte, each output byte represents one UTF-16 code unit:
    * either an ASCII character or an unpaired surrogate replaced with '?'. Therefore the
    * original character at a replacement's byte offset must also be '?'. Non-ASCII output
    * breaks that correspondence, so replacements from that word onward use full validation.
    */
  private def validateEncodingReplacement(value: String, bytes: Array[Byte]): Unit =
    var index = 0
    while index <= bytes.length - 8 do
      val loaded = ByteArrayAccess.getLongLE(bytes, index)
      if (loaded & highBytes) != 0L then
        if containsEncodingReplacement(bytes, index) then validateUtf16(value)
        return
      val word = loaded ^ replacementWord
      if ((word - lowBytes) & ~word & highBytes) != 0L then
        var offset = index
        while offset < index + 8 do
          if bytes(offset) == encodedReplacementByte && value.charAt(offset) != encodedReplacementByte.toChar then
            invalidSurrogate(offset)
          offset += 1
      index += 8
    while index < bytes.length do
      val byte = bytes(index)
      if byte < 0 then
        if containsEncodingReplacement(bytes, index) then validateUtf16(value)
        return
      if byte == encodedReplacementByte then
        if value.charAt(index) != encodedReplacementByte.toChar then invalidSurrogate(index)
      index += 1

  private def containsEncodingReplacement(bytes: Array[Byte], from: Int): Boolean =
    var index = from
    while index <= bytes.length - 8 do
      val loaded = ByteArrayAccess.getLongLE(bytes, index)
      val word = loaded ^ replacementWord
      if ((word - lowBytes) & ~word & highBytes) != 0L then return true
      index += 8
    while index < bytes.length do
      if bytes(index) == encodedReplacementByte then return true
      index += 1
    false

  private def validateUtf16(value: String): Unit =
    val length = value.length
    var index = 0
    while index < length do
      val char = value.charAt(index)
      if Character.isSurrogate(char) then
        if !Character.isHighSurrogate(char) || index + 1 == length ||
            !Character.isLowSurrogate(value.charAt(index + 1)) then
          invalidSurrogate(index)
        index += 2
      else
        index += 1

  private def invalidSurrogate(index: Int): Nothing =
    throw new IllegalArgumentException(s"requirement failed: Unpaired UTF-16 surrogate at index $index")

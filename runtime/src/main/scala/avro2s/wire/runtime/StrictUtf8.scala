package avro2s.wire.runtime

import java.nio.charset.StandardCharsets

/** Public JDK conversion paths, with strict validation when replacement could have occurred.
  * String's charset overloads replace malformed input with the charset's default replacement.
  * Absence of that replacement therefore proves that conversion accepted the original input.
  * A literal replacement in valid text takes the strict fallback; it is never rejected merely
  * because it matches the sentinel. No decoder/encoder instance is shared between calls.
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
    if containsEncodingReplacement(bytes) then validateUtf16(value)
    bytes

  /** Plain VarHandle reads allow unaligned byte-array offsets. Every load is in bounds.
    * The zero-byte test works in either byte order and only asks whether any byte matches.
    */
  private def containsEncodingReplacement(bytes: Array[Byte]): Boolean =
    var index = 0
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
    var index = 0
    while index < value.length do
      val char = value.charAt(index)
      if Character.isHighSurrogate(char) then
        require(index + 1 < value.length && Character.isLowSurrogate(value.charAt(index + 1)),
          s"Unpaired UTF-16 surrogate at index $index")
        index += 2
      else
        require(!Character.isLowSurrogate(char), s"Unpaired UTF-16 surrogate at index $index")
        index += 1

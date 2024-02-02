/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.digitalpebble.tika.detect;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.io.ByteOrderMark;
import org.apache.commons.io.IOUtils;
import org.apache.commons.io.input.BOMInputStream;
import org.apache.tika.detect.EncodingDetector;
import org.apache.tika.metadata.Metadata;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;
import org.jsoup.select.Elements;

import com.ibm.icu.text.CharsetDetector;
import com.ibm.icu.text.CharsetMatch;

/**
 * Wraps the Charset identification logic from StormCrawler.
 */
public class SCCharsetDetector implements EncodingDetector {

	private static final Charset DEFAULT_CHARSET = StandardCharsets.UTF_8;

	private static final Pattern charsetPattern = Pattern.compile("(?i)\\bcharset=\\s*(?:[\"'])?([^\\s,;\"']*)");

	private static final String CONTENT_TYPE = "content-type";

	private boolean fastMethod = false;

	private int maxLength = -1;

	public Charset detect(InputStream input, Metadata metadata) throws IOException {
		if (input == null) {
			return null;
		}
		// TODO mark and reset input stream
		byte[] content = IOUtils.toByteArray(input);
		
		// trim to maxLength once and for all
		if (maxLength != -1 && maxLength < content.length) {
			content = IOUtils.toByteArray(input);
		}
		
		String charset = null;
		if (isFastMethod()) {
			charset = getCharsetFast(metadata, content);
		}
		else {
			charset = getCharset(metadata, content);
		}
		return Charset.forName(charset);
	}
	
	public boolean isFastMethod() {
		return fastMethod;
	}

	public void setFastMethod(boolean fastMethod) {
		this.fastMethod = fastMethod;
	}

	public int getMaxLength() {
		return maxLength;
	}

	public void setMaxLength(int maxLength) {
		this.maxLength = maxLength;
	}

	/**
	 * Identifies the charset of a document based on the following logic: guess from
	 * the ByteOrderMark - else return any charset specified in the http headers if
	 * any, otherwise return the one from the html metadata; finally use ICU's
	 * charset detector to make an educated guess and if that fails too returns
	 * UTF-8. This approach is expected to be faster but maybe less reliable than
	 * getCharset.
	 *
	 * @since 1.18
	 */
	public static String getCharsetFast(final Metadata metadata, final byte[] content) {

		// let's look at the BOM first
		String charset = getCharsetFromBOM(content);
		if (charset != null) {
			return charset;
		}

		// then look at what we get from HTTP headers
		charset = getCharsetFromHTTP(metadata);
		if (charset != null) {
			return charset;
		}

		charset = getCharsetFromMeta(content);
		if (charset != null) {
			return charset;
		}

		// let's guess from the text without a hint
		charset = getCharsetFromText(content, null);
		if (charset != null) {
			return charset;
		}

		// return the default charset
		return DEFAULT_CHARSET.name();
	}

	/**
	 * Identifies the charset of a document based on the following logic: guess from
	 * the ByteOrderMark - else if the same charset is specified in the http headers
	 * and the html metadata then use it - otherwise use ICU's charset detector to
	 * make an educated guess and if that fails too returns UTF-8.
	 */
	public static String getCharset(Metadata metadata, byte[] content) {

		// let's look at the BOM first
		String BOMCharset = getCharsetFromBOM(content);
		if (BOMCharset != null) {
			return BOMCharset;
		}

		// then look at what we get from HTTP headers and HTML content
		String httpCharset = getCharsetFromHTTP(metadata);
		String htmlCharset = getCharsetFromMeta(content);

		// both exist and agree
		if (httpCharset != null && htmlCharset != null && httpCharset.equalsIgnoreCase(htmlCharset)) {
			return httpCharset;
		}

		// let's guess from the text - using a hint or not
		String hintCharset = null;
		if (httpCharset != null && htmlCharset == null) {
			hintCharset = httpCharset;
		} else if (httpCharset == null && htmlCharset != null) {
			hintCharset = htmlCharset;
		}

		String textCharset = getCharsetFromText(content, hintCharset);
		if (textCharset != null) {
			return textCharset;
		}

		// return the default charset
		return DEFAULT_CHARSET.name();
	}

	/** Returns the charset declared by the server if any */
	private static String getCharsetFromHTTP(Metadata metadata) {
		String ct = metadata.get(CONTENT_TYPE);
		if (ct == null)
			return null;
		return getCharsetFromContentType(ct);
	}

	/** Detects any BOMs and returns the corresponding charset */
	private static String getCharsetFromBOM(final byte[] byteData) {
		try (BOMInputStream bomIn = BOMInputStream.builder().setByteArray(byteData).get()) {
			ByteOrderMark bom = bomIn.getBOM();
			if (bom != null) {
				return bom.getCharsetName();
			}
		} catch (IOException e) {
			return null;
		}
		return null;
	}

	/**
	 * Use a third party library as last resort to guess the charset from the bytes.
	 */
	private static String getCharsetFromText(byte[] content, String declaredCharset) {
		String charset = null;
		// filter HTML tags
		CharsetDetector charsetDetector = new CharsetDetector();
		charsetDetector.enableInputFilter(true);
		// give it a hint
		if (declaredCharset != null)
			charsetDetector.setDeclaredEncoding(declaredCharset);
		charsetDetector.setText(content);
		try {
			CharsetMatch charsetMatch = charsetDetector.detect();
			charset = validateCharset(charsetMatch.getName());
		} catch (Exception e) {
			charset = null;
		}
		return charset;
	}

	/**
	 * Attempt to find a META tag in the HTML that hints at the character set used
	 * to write the document.
	 */
	private static String getCharsetFromMeta(byte buffer[]) {
		// convert to UTF-8 String -- which hopefully will not mess up the
		// characters we're interested in...
		int len = buffer.length;
		String html = new String(buffer, 0, len, DEFAULT_CHARSET);

		// fast search for e.g. <meta charset="utf-8">
		// might not get it 100% but should be frequent enough
		// and faster than parsing
		int start = html.indexOf("<meta charset=\"");
		if (start != -1) {
			int end = html.indexOf('"', start + 15);
			// https://github.com/DigitalPebble/storm-crawler/issues/870
			// try on a slightly larger section of text if it is trimmed
//			if (end == -1 && ((maxlength + 10) < buffer.length)) {
//				return getCharsetFromMeta(buffer, maxlength + 10);
//			}
			if (end == -1) {
				// there is an open tag meta but not closed = we have broken content!
				return null;
			}
			return validateCharset(html.substring(start + 15, end));
		}

		String foundCharset = null;

		try {
			Document doc = Parser.htmlParser().parseInput(html, "dummy");

			// look for <meta http-equiv="Content-Type"
			// content="text/html;charset=gb2312"> or HTML5 <meta
			// charset="gb2312">
			Elements metaElements = doc.select("meta[http-equiv=content-type], meta[charset]");
			for (Element meta : metaElements) {
				if (meta.hasAttr("http-equiv"))
					foundCharset = getCharsetFromContentType(meta.attr("content"));
				if (foundCharset == null && meta.hasAttr("charset"))
					foundCharset = meta.attr("charset");
				if (foundCharset != null)
					return foundCharset;
			}
		} catch (Exception e) {
			foundCharset = null;
		}

		return foundCharset;
	}

	/**
	 * Parse out a charset from a content type header. If the charset is not
	 * supported, returns null (so the default will kick in.)
	 *
	 * @param contentType e.g. "text/html; charset=EUC-JP"
	 * @return "EUC-JP", or null if not found. Charset is trimmed and uppercased.
	 */
	private static String getCharsetFromContentType(String contentType) {
		if (contentType == null)
			return null;
		Matcher m = charsetPattern.matcher(contentType);
		if (m.find()) {
			String charset = m.group(1).trim();
			charset = charset.replace("charset=", "");
			return validateCharset(charset);
		}
		return null;
	}

	private static String validateCharset(String cs) {
		if (cs == null || cs.length() == 0)
			return null;
		cs = cs.trim().replaceAll("[\"']", "");
		try {
			if (Charset.isSupported(cs))
				return cs;
			cs = cs.toUpperCase(Locale.ENGLISH);
			if (Charset.isSupported(cs))
				return cs;
		} catch (IllegalCharsetNameException e) {
		}
		return null;
	}
}

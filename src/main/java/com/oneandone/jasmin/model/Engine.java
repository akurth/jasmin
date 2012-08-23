package com.oneandone.jasmin.model;

import net.sf.beezle.sushi.fs.GetLastModifiedException;
import net.sf.beezle.sushi.graph.CyclicDependency;
import net.sf.beezle.sushi.io.Buffer;
import net.sf.beezle.sushi.util.Strings;

import javax.servlet.http.HttpServletResponse;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

public class Engine {
    private static final String UTF_8 = "utf-8";

    public final Repository repository;
    public final HashCache hashCache;
    public final ContentCache contentCache;

    public Engine(Repository repository) {
        this.repository = repository;
        this.hashCache = new HashCache(1000000);
        this.contentCache = new ContentCache(10000000);
    }

    /**
     * Output is prepared in-memory before the response is writter because
     * a) that's the commen case where output is cached. If output is to big for this, that whole caching doesn't work
     * b) I send sent proper error pages
     * c) I can return the number of bytes actually written
     * @return bytes written
     */
    public int process(String path, HttpServletResponse response, boolean gzip) throws IOException {
        Content content;
        byte[] bytes;
        Writer writer;

        try {
            content = doProcess(path);
        } catch (IOException e) {
            // TODO: production only?
            // TODO: warning sender
            response.setStatus(500);
            response.setContentType("text/html");
            writer = response.getWriter();
            writer.write("<html><body><h1>" + e.getMessage() + "</h1></body></html>");
            writer.close();
            return -1;
        }
        if (gzip) {
            // see "High Performance Websites", by Steve Souders
            response.setHeader("Content-Encoding", "gzip");
            response.addHeader("Cache-Control", "private");
            bytes = content.bytes;
        } else {
            bytes = unzip(content.bytes);
        }
        response.setBufferSize(0);
        response.setContentType(content.mimeType);
        response.setCharacterEncoding(UTF_8); // TODO: inspect header - does this have an effect?
        if (content.lastModified != -1) {
            response.setDateHeader("Last-Modified", content.lastModified);
        }
        response.getOutputStream().write(bytes);
        return bytes.length;
    }

    public String process(String path) throws IOException {
        Content content;

        content = doProcess(path);
        return new String(unzip(content.bytes), UTF_8);
    }

    /* @return -1 for when unknown */
    public long getLastModified(String path) throws GetLastModifiedException {
        String hash;
        Content content;

        hash = hashCache.probe(path);
        if (hash != null) {
            content = contentCache.probe(hash);
            if (content != null) {
                return content.lastModified;
            }
        }
        return -1;
    }

    public void free() {
        hashCache.resize(0);
        contentCache.resize(0);
    }

    //--

    /** @return gzip compressed content */
    private Content doProcess(String path) throws IOException {
        long startContent;
        long endContent;
        String hash;
        Content content;
        ByteArrayOutputStream result;
        OutputStream dest;
        Writer writer;
        References references;
        byte[] bytes;

        hash = hashCache.lookup(path);
        if (hash != null) {
            content = contentCache.lookup(hash);
            if (content != null) {
                return content;
            }
        }
        startContent = System.currentTimeMillis();
        try {
            references = repository.resolve(Request.parse(path));
        } catch (CyclicDependency e) {
            throw new RuntimeException(e.toString(), e);
        } catch (IOException e) {
            throw new IOException(path + ": " + e.getMessage(), e);
        }
        result = new ByteArrayOutputStream(); // TODO: pool!
        dest = new GZIPOutputStream(result);
        writer = new OutputStreamWriter(dest);
        references.writeTo(writer);
        writer.close();
        bytes = result.toByteArray();
        endContent = System.currentTimeMillis();
        hash = hash(bytes);
        content = new Content(references.type.getMime(), references.getLastModified(), bytes);
        hashCache.add(path, hash, endContent /* that's where hash computation starts */, 0 /* too small for meaningful measures */);
        contentCache.add(hash, content, startContent, endContent - startContent);
        return content;
    }

    private static final MessageDigest DIGEST;

    static {
        try {
            DIGEST = MessageDigest.getInstance("SHA");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String hash(byte[] bytes) {
        byte[] result;

        synchronized (DIGEST) {
            result = DIGEST.digest(bytes);
        }
        return Strings.toHex(result);
    }

    private static byte[] unzip(byte[] bytes) {
        // TODO: pool?
        try {
            return new Buffer().readBytes(new GZIPInputStream(new ByteArrayInputStream(bytes)));
        } catch (IOException e) {
            throw new IllegalStateException("unexpected IOException from ByteArrayInputStream: " + e.getMessage(), e);
        }
    }

}

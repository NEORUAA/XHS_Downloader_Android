package com.neoruaa.xhsdn;

import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

public class LivePhotoCreator {
    private static final String TAG = "LivePhotoCreator";

    /**
     * Creates a live photo by embedding video into image with XMP metadata
     * @param imageFile The image file to use as the primary content
     * @param videoFile The video file to embed
     * @param outputFile The output live photo file
     * @return True if successful, false otherwise
     */
    public static boolean createLivePhoto(File imageFile, File videoFile, File outputFile) {
        try {
            // Read the image and video files
            byte[] imageBytes = readFileToBytes(imageFile);
            byte[] videoBytes = readFileToBytes(videoFile);
            
            // Find the position for the video in the image file
            byte[] modifiedImageBytes = insertXMPIntoJpeg(imageBytes, videoBytes.length);
            
            // Create the live photo by appending the video to the modified image
            try (FileOutputStream fos = new FileOutputStream(outputFile)) {
                fos.write(modifiedImageBytes);
                fos.write(videoBytes);
            }
            
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Error creating live photo: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }
    
    /**
     * Inserts XMP metadata into a JPEG file
     * @param jpegBytes The original JPEG bytes
     * @param videoSize The size of the embedded video in bytes
     * @return Modified JPEG bytes with XMP metadata inserted
     */
    private static byte[] insertXMPIntoJpeg(byte[] jpegBytes, int videoSize) throws IOException {
        // Find the position for APP1 marker (where XMP should go)
        // After SOI (0xFFD8) marker, before any image data
        int insertPosition = findXmpInsertPosition(jpegBytes);
        
        // Generate XMP metadata
        String xmpMetadata = generateXMPMetadata(videoSize);
        byte[] xmpBytes = xmpMetadata.getBytes("UTF-8");
        
        // Create the APP1 XMP segment
        byte[] xmpSegment = createXmpApp1Segment(xmpBytes);
        
        // Insert the XMP segment into the JPEG
        byte[] result = new byte[jpegBytes.length + xmpSegment.length];
        
        // Copy bytes before insertion point
        System.arraycopy(jpegBytes, 0, result, 0, insertPosition);
        
        // Copy XMP segment
        System.arraycopy(xmpSegment, 0, result, insertPosition, xmpSegment.length);
        
        // Copy bytes after insertion point
        System.arraycopy(jpegBytes, insertPosition, result, insertPosition + xmpSegment.length, 
                         jpegBytes.length - insertPosition);
        
        return result;
    }
    
    /**
     * Finds the appropriate insert position for XMP in a JPEG file
     * @param jpegBytes The JPEG file bytes
     * @return The position to insert the XMP segment
     */
    private static int findXmpInsertPosition(byte[] jpegBytes) {
        // JPEG structure: SOI (0xFFD8) + APP markers + image data
        // XMP typically goes in an APP1 marker right after SOI
        if (jpegBytes.length < 2) {
            return 0;
        }
        
        // Check for SOI marker at beginning (0xFFD8)
        if (jpegBytes[0] == (byte) 0xFF && jpegBytes[1] == (byte) 0xD8) {
            // Look for existing APP markers after SOI to insert our XMP before actual image data
            // Start after SOI and look for next marker
            int pos = 2;
            
            while (pos < jpegBytes.length - 1) {
                if (jpegBytes[pos] == (byte) 0xFF) {
                    // Found marker byte
                    byte marker = jpegBytes[pos + 1];
                    if (marker != (byte) 0x00 && marker != (byte) 0xFF) {
                        // This is a valid marker
                        if (marker >= 0xE0 && marker <= 0xEF) {
                            // This is an APP marker, could be APP0, APP1, etc.
                            // Skip this APP marker to find the next one
                            if (pos + 3 < jpegBytes.length) {
                                // Read the length of this APP segment
                                int segmentLength = ((jpegBytes[pos + 2] & 0xFF) << 8) | (jpegBytes[pos + 3] & 0xFF);
                                pos += 2 + segmentLength; // Skip marker and its data
                                continue;
                            }
                        } else if (marker == 0xC0 || marker == 0xC1 || marker == 0xC2 || 
                                   marker == 0xC3 || marker == 0xDB || marker == 0xDA) {
                            // This is a SOF (Start of Frame) or SOS (Start of Scan) marker
                            // This means we're at the image data, insert XMP before this
                            return pos;
                        }
                    }
                }
                pos++;
            }
            
            // If we get here, no image data marker was found, insert after SOI
            return 2;
        }
        
        // If no SOI found, insert at beginning
        return 0;
    }
    
    /**
     * Generates XMP metadata for live photo
     * @param videoSize The size of the embedded video in bytes
     * @return XMP metadata string
     */
    private static String generateXMPMetadata(int videoSize) {
        return String.format(
            "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\" x:xmptk=\"Adobe XMP Core 5.1.0-jc003\">" +
            "<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">" +
            "<rdf:Description rdf:about=\"\"" +
            "    xmlns:GCamera=\"http://ns.google.com/photos/1.0/camera/\"" +
            "    xmlns:OpCamera=\"http://ns.oplus.com/photos/1.0/camera/\"" +
            "    xmlns:MiCamera=\"http://ns.xiaomi.com/photos/1.0/camera/\"" +
            "    xmlns:Container=\"http://ns.google.com/photos/1.0/container/\"" +
            "    xmlns:Item=\"http://ns.google.com/photos/1.0/container/item/\"" +
            "  GCamera:MotionPhoto=\"1\"" +
            "  GCamera:MotionPhotoVersion=\"1\"" +
            "  GCamera:MotionPhotoPresentationTimestampUs=\"0\"" +
            "  OpCamera:MotionPhotoPrimaryPresentationTimestampUs=\"0\"" +
            "  OpCamera:MotionPhotoOwner=\"xhs\"" +
            "  OpCamera:OLivePhotoVersion=\"2\"" +
            "  OpCamera:VideoLength=\"%d\"" +
            "  GCamera:MicroVideoVersion=\"1\"" +
            "  GCamera:MicroVideo=\"1\"" +
            "  GCamera:MicroVideoOffset=\"%d\"" +
            "  GCamera:MicroVideoPresentationTimestampUs=\"0\"" +
            "  MiCamera:XMPMeta=\"&lt;?xml version='1.0' encoding='UTF-8' standalone='yes' ?&gt;\">" +
            "  <Container:Directory>" +
            "    <rdf:Seq>" +
            "      <rdf:li rdf:parseType=\"Resource\">" +
            "        <Container:Item" +
            "          Item:Mime=\"image/jpeg\"" +
            "          Item:Semantic=\"Primary\"" +
            "          Item:Length=\"0\"" +
            "          Item:Padding=\"0\"/>" +
            "      </rdf:li>" +
            "      <rdf:li rdf:parseType=\"Resource\">" +
            "        <Container:Item" +
            "          Item:Mime=\"video/mp4\"" +
            "          Item:Semantic=\"MotionPhoto\"" +
            "          Item:Length=\"%d\"/>" +
            "      </rdf:li>" +
            "    </rdf:Seq>" +
            "  </Container:Directory>" +
            "</rdf:Description>" +
            "</rdf:RDF>" +
            "</x:xmpmeta>",
            videoSize, videoSize, videoSize
        );
    }
    
    /**
     * Creates an APP1 XMP segment with proper JPEG header
     * @param xmpData The XMP data as bytes
     * @return Byte array representing the APP1 XMP segment
     */
    private static byte[] createXmpApp1Segment(byte[] xmpData) throws IOException {
        // XMP header: "http://ns.adobe.com/xap/1.0/\0"
        byte[] xmpHeader = "http://ns.adobe.com/xap/1.0/\0".getBytes("UTF-8");
        
        // Calculate total segment length: xmp header + xmp data + 2 bytes for length field
        int segmentLengthWithoutLengthField = xmpHeader.length + xmpData.length;
        int totalSegmentLength = segmentLengthWithoutLengthField + 2; // +2 for the length field itself
        
        // Create the segment
        ByteArrayOutputStream segment = new ByteArrayOutputStream();
        
        // Write APP1 marker (0xFFE1)
        segment.write(0xFF);
        segment.write(0xE1);
        
        // Write length field (2 bytes, big-endian)
        segment.write((totalSegmentLength >> 8) & 0xFF);
        segment.write(totalSegmentLength & 0xFF);
        
        // Write XMP header
        segment.write(xmpHeader);
        
        // Write XMP data
        segment.write(xmpData);
        
        return segment.toByteArray();
    }
    
    /**
     * Reads a file into a byte array
     * @param file The file to read
     * @return Byte array containing the file contents
     * @throws IOException
     */
    private static byte[] readFileToBytes(File file) throws IOException {
        try (FileInputStream fis = new FileInputStream(file);
             ByteArrayOutputStream buffer = new ByteArrayOutputStream()) {
            
            int nRead;
            byte[] data = new byte[16384]; // 16KB buffer
            
            while ((nRead = fis.read(data, 0, data.length)) != -1) {
                buffer.write(data, 0, nRead);
            }
            
            return buffer.toByteArray();
        }
    }
}
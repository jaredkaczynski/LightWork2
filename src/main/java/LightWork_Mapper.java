import processing.core.*;
import processing.data.*;
import processing.event.*;
import processing.opengl.*;

import processing.svg.*;
import processing.video.*;
import gab.opencv.*;

import java.awt.Rectangle;

import com.heroicrobot.controlsynthesis.*;
import com.heroicrobot.dropbit.common.*;
import com.heroicrobot.dropbit.devices.*;
import com.heroicrobot.dropbit.devices.pixelpusher.*;
import com.heroicrobot.dropbit.discovery.*;
import com.heroicrobot.dropbit.registry.*;

import java.util.*;
import java.io.*;

import artnetP5.*;
import eDMX.*;
import oscP5.*;
import netP5.*;

import java.net.*;
import java.util.Arrays;

import controlP5.*;

import java.util.*;

import java.util.HashMap;
import java.util.ArrayList;
import java.io.File;
import java.io.BufferedReader;
import java.io.PrintWriter;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.IOException;

public class LightWork_Mapper extends PApplet {

/*
 *  Lightwork-Mapper
 *
 *  This sketch uses computer vision to automatically generate mapping for LEDs.
 *  Currently, Fadecandy, PixelPusher, Artnet and sACN are supported.
 *
 *  Required Libraries available from P
 rocessing library manager:
 *  PixelPusher, OpenCV, ControlP5, eDMX, oscP5
 *
 *  Additional Libraries:
 *  ArtNet P5 - included in this repo or from https://github.com/sadmb/artnetP5
 *
 *  Copyright (C) 2017 PWRFL
 *
 *  @authors Leó Stefánsson and Tim Rolls
 *
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program.  If not, see <https://www.gnu.org/licenses/>.
 *
 */


    Capture cam;
    //Capture cam2;
    OpenCV opencv;

    ControlP5 cp5;
    Animator animator;
    Interface network;
    BlobManager blobManager;

    int captureIndex; // For capturing each binary state (decoding later).
    boolean isMapping = false;

    enum VideoMode {
        CAMERA, FILE, IMAGE_SEQUENCE, CALIBRATION, OFF
    }

    ;

    VideoMode videoMode;

    int on = color(255, 255, 255);
    int off = color(0, 0, 0);

    int camWidth = 640;
    int camHeight = 480;
    float camAspect;

    PGraphics camFBO;
    PGraphics cvFBO;
    PGraphics blobFBO;

    int cvThreshold = 25;
    float cvContrast = 1.15f;
    int ledBrightness = 45;

    ArrayList<LED> leds; // Global, used by Animator and Interface classes
    PVector[] leftMap;
    PVector[] rightMap;

    int FPS = 30;
    String savePath = "../LightWork_Scraper/data/layout.csv"; //defaults to scraper data folder

    PImage videoInput;
    PImage cvOutput;

    // Image sequence parameters
    int numFrames = 10;  // The number of frames in the animation
    int currentFrame = 0;
    ArrayList<PGraphics> images;
    PImage backgroundImage = new PImage();
    PGraphics diff; // Background subtracted from Binary Pattern Image
    int imageIndex = 0;
    int captureTimer = 0;
    boolean shouldStartPatternMatching; // Only start matching once we've decoded a full sequence
    boolean shouldStartDecoding; // Start decoding once we've captured all binary pattern states

    public void setup() {

        frameRate(FPS);
        warranty();

        camAspect = (float) camWidth / (float) camHeight;
        println("Cam Aspect: " + camAspect);

        videoMode = VideoMode.CAMERA;

        println("creating FBOs");
        camFBO = createGraphics(camWidth, camHeight, P3D);
        cvFBO = createGraphics(camWidth, camHeight, P3D);
        blobFBO = createGraphics(camWidth, camHeight, P3D);

        println("making arraylists for LEDs and bloblist");
        leds = new ArrayList<LED>();

        //Load Camera in a thread, because polling USB can hang the software, and fail OpenGL initialization
        println("initializing camera");
        thread("setupCam");
        //Thread may be causing strange state issues with PixelPusher
        //setupCam();

        // Network
        println("setting up network Interface");
        network = new Interface();
        //These can be set via UI, but can be faster to set them here.
        //network.setNumStrips(3);
        //network.setNumLedsPerStrip(16);
        //network.setNumArtnetChannels(3);
        //network.setNumArtnetFixtures(16);

        // Animator
        println("creating animator");
        animator = new Animator(); //ledsPerstrip, strips, brightness
        animator.setFrameSkip(frameSkip);
        animator.setLedBrightness(ledBrightness);
        animator.setFrameSkip(frameSkip);
        animator.setAllLEDColours(off); // Clear the LED strips
        animator.update();

        // Check for high resolution display
        println("setup gui multiply");
        if (displayWidth >= 2560) {
            guiMultiply = 2;
        }
        // Set up window for 2d mapping
        window2d();

        println("calling buildUI on a separate thread");
        thread("buildUI"); // This takes more than 5 seconds and will break OpenGL if it's not on a separate thread

        // Make sure there's always something in videoInput
        println("allocating videoInput with empty image");
        videoInput = createImage(camWidth, camHeight, RGB);

        // OpenCV Setup
        println("Setting up openCV");
        opencv = new OpenCV(this, videoInput);

        // Blob Manager
        blobManager = new BlobManager(this, opencv);

        // Image sequence
        captureIndex = 0;
        images = new ArrayList<PGraphics>();
        diff = createGraphics(camWidth, camHeight, P2D);
        backgroundImage = createImage(camWidth, camHeight, RGB);

        shouldStartPatternMatching = false;
        shouldStartDecoding = false;
        background(0);
    }

    // -----------------------------------------------------------
// -----------------------------------------------------------
    public void draw() {
        // LOADING SCREEN
        if (!isUIReady) {
            loading();
            return;
        } else if (!cp5.isVisible()) {
            cp5.setVisible(true);
        }
        // END LOADING SCREEN

        //UI is drawn on canvas background, update to clear last frame's UI changes
        background(0xff222222);

        // Update the LEDs (before we do anything else).
        animator.update();

        // -------------------------------------------------------
        //              VIDEO INPUT + OPENCV PROCESSING
        // -------------------------------------------------------
        if (cam != null && cam.available() == true) {
            cam.read();
            if (videoMode != VideoMode.IMAGE_SEQUENCE) { //TODO: review
                videoInput = cam;
            }
        }

        // Binary Image Sequence Capture
        if (videoMode == VideoMode.IMAGE_SEQUENCE && isMapping) {
            // Capture sequence if it doesn't exist
            if (images.size() < numFrames) {
                PGraphics pg = createGraphics(camWidth, camHeight, P2D);
                pg.beginDraw();
                pg.image(videoInput, 0, 0);
                pg.endDraw();
                captureTimer++;
                if (captureTimer == animator.frameSkip / 2) { // Capture halfway through animation frame
                    println("adding image frame to sequence");
                    images.add(pg);
                } else if (captureTimer >= animator.frameSkip) { // Reset counter when frame is done
                    captureTimer = 0;
                }
            }
            // If sequence exists assign it to videoInput
            else {
                shouldStartDecoding = true;
                videoInput = images.get(currentFrame);
                currentFrame++;
                if (currentFrame >= numFrames) {
                    shouldStartPatternMatching = true; // We've decoded a full sequence, start pattern matchin
                    currentFrame = 0;
                }
            }
        }

        processCV(); // Call this AFTER videoInput has been assigned


        // -------------------------------------------------------
        //                        DISPLAY
        // -------------------------------------------------------


        // Display the camera input
        camFBO.beginDraw();
        camFBO.image(videoInput, 0, 0);
        camFBO.endDraw();
        image(camFBO, 0, (70 * guiMultiply), camDisplayWidth, camDisplayHeight);

        // Display OpenCV output and dots for detected LEDs (dots for sequential mapping only).
        cvFBO.beginDraw();
        PImage snap = opencv.getOutput();
        cvFBO.image(snap, 0, 0);

        if (leds.size() > 0) {
            for (LED led : leds) {
                if (led.coord.x != 0 && led.coord.y != 0) {
                    cvFBO.noFill();
                    cvFBO.stroke(255, 0, 0);
                    cvFBO.ellipse(led.coord.x, led.coord.y, 10, 10);
                }
            }
        }
        cvFBO.endDraw();
        image(cvFBO, camDisplayWidth, 70 * guiMultiply, camDisplayWidth, camDisplayHeight);

        // Display blobs
        blobFBO.beginDraw();
        blobManager.display();
        blobFBO.endDraw();

        // Draw a sequence of the sequential captured frames
        if (images.size() > 0) {
            for (int i = 0; i < images.size(); i++) {
                image(images.get(i), i * width / 10, camDisplayHeight, width / 10, height / 10);
            }
            stroke(0xff00aaff);
            strokeWeight(3);
            noFill();
            rect(currentFrame * width / 10, camDisplayHeight, width / 10, height / 10); //TODO: make this length adjustable
        }

        showLEDOutput();
        showBlobCount();

        // -------------------------------------------------------
        //                      MAPPING
        // -------------------------------------------------------

        // Calibration mode, use this to tweak your parameters before mapping
        if (videoMode == VideoMode.CALIBRATION) {
            blobManager.update(opencv.getOutput());
        }

        // Decode image sequence
        else if (videoMode == VideoMode.IMAGE_SEQUENCE && images.size() >= numFrames) {
            blobManager.update(opencv.getSnapshot());
            blobManager.display();
            if (shouldStartDecoding) {
                decode();
            }

            if (shouldStartPatternMatching) {
                matchBinaryPatterns();
            }
        } else if (isMapping && !patternMapping) {
            blobManager.update(opencv.getOutput());
            if (frameCount % frameSkip == 0) {
                sequentialMapping();
            }
        }
    }

// -----------------------------------------------------------
// Mapping methods
// -----------------------------------------------------------


    public void sequentialMapping() {
        if (blobManager.blobList.size() != 0) {
            Rectangle rect = blobManager.blobList.get(blobManager.blobList.size() - 1).contour.getBoundingBox();
            PVector loc = new PVector();
            loc.set((float) rect.getCenterX(), (float) rect.getCenterY());

            int index = animator.getLedIndex();
            leds.get(index).setCoord(loc);
            println(loc);
        }
    }

    public void matchBinaryPatterns() {
        for (int i = 0; i < leds.size(); i++) {
            if (leds.get(i).foundMatch) {
                continue;
            }
            String targetPattern = leds.get(i).binaryPattern.binaryPatternString.toString();
            for (int j = 0; j < blobManager.blobList.size(); j++) {
                String decodedPattern = blobManager.blobList.get(j).detectedPattern.decodedString.toString();
                //println("checking match with decodedPattern: "+decodedPattern);
                if (targetPattern.equals(decodedPattern)) {
                    leds.get(i).foundMatch = true;
                    Rectangle rect = blobManager.blobList.get(j).contour.getBoundingBox();
                    PVector pvec = new PVector();
                    pvec.set((float) rect.getCenterX(), (float) rect.getCenterY());
                    leds.get(i).setCoord(pvec);
                    println("LED: " + i + " Blob: " + j + " --- " + targetPattern + " --- " + decodedPattern);
                }
            }
        }

        // Mapping is done, Save CSV for LEFT or RIGHT channels
        if (stereoMode == true && mapRight == true) {
            rightMap = new PVector[leds.size()];
            arrayCopy(getLEDVectors(leds).toArray(), rightMap);
            saveCSV(leds, dataPath("right.csv"));
        } else if (stereoMode == true) {
            leftMap = new PVector[leds.size()];
            arrayCopy(getLEDVectors(leds).toArray(), leftMap);
            saveCSV(leds, dataPath("left.csv"));
        }

        network.saveOSC(normCoords(leds));

        map();
    }

    public void decode() {
        // Update brightness levels for all the blobs
        if (blobManager.blobList.size() > 0) {
            for (int i = 0; i < blobManager.blobList.size(); i++) {
                // Get the blob brightness to determine it's state (HIGH/LOW)
                //println("decoding this blob: "+blobList.get(i).id);
                Rectangle r = blobManager.blobList.get(i).contour.getBoundingBox();
                // TODO: Which texture do we decode?
                PImage snap = opencv.getSnapshot();
                PImage cropped = snap.get(r.x, r.y, r.width, r.height); // TODO: replace with videoInput
                int br = 0;
                for (int c : cropped.pixels) {
                    br += brightness(c);
                }

                br = br / cropped.pixels.length;
                blobManager.blobList.get(i).decode(br); // Decode the pattern
            }
        }
    }

    // OpenCV Processing
    public void processCV() {
        diff.beginDraw();
        //diff.background(0);
        diff.blendMode(NORMAL);
        diff.image(videoInput, 0, 0);
        diff.blendMode(SUBTRACT);
        diff.image(backgroundImage, 0, 0);
        diff.endDraw();
        opencv.loadImage(diff);
        opencv.contrast(cvContrast);
        opencv.threshold(cvThreshold);
    }

    // Count LEDs that have been matched
    public int listMatchedLEDs() {
        int count = 0;
        for (LED led : leds) {
            if (led.foundMatch == true) count++;
        }
        return count;
    }

    // Return LED locations as PVectors
    public ArrayList<PVector> getLEDVectors(ArrayList<LED> l) {
        ArrayList<PVector> loc = new ArrayList<PVector>();
        for (int i = 0; i < l.size(); i++) {
            PVector temp = new PVector();
            temp = l.get(i).coord;
            loc.add(temp);
        }
        return loc;
    }

    // Estimate LED z location from left and right captures
    public void calculateZ(PVector[] l, PVector[] r) {
        for (int i = 0; i < l.length; i++) {
            if (l[i].x != 0 && l[i].y != 0 && r[i].x != 0 && r[i].y != 0) {
                float z = l[i].dist(r[i]); // change from left to right capture
                leds.get(i).coord.set(r[i].x, r[i].y, z);
            }
        }
    }

    // Deterimine bounding box of points for normalizing
    public float[] getMinMaxCoords(ArrayList<PVector> pointsCopy) {
        //ArrayList<PVector> pointsCopy = new ArrayList<PVector>(points);

        for (int i = pointsCopy.size() - 1; i >= 0; i--) {
            PVector temp = pointsCopy.get(i);
            if (temp.x == 0 && temp.y == 0) {
                pointsCopy.remove(i);
            }
        }

        float xArr[] = new float[pointsCopy.size()];
        float yArr[] = new float[pointsCopy.size()];
        float zArr[] = new float[pointsCopy.size()];

        int index = 0;
        for (PVector temp : pointsCopy) {
            xArr[index] = temp.x;
            yArr[index] = temp.y;
            zArr[index] = temp.z;

            index++;
        }

        float minX = min(xArr);
        float minY = min(yArr);
        float minZ = min(zArr);
        float maxX = max(xArr);
        float maxY = max(yArr);
        float maxZ = max(zArr);

        float[] out = {minX, minY, minZ, maxX, maxY, maxZ};
        return out;
    }

    // Normalize point coordinates
    public ArrayList<LED> normCoords(ArrayList<LED> in) {

        //check for at least 1 matched LED and we are pattern mapping
        if (listMatchedLEDs() == 0 && patternMapping) {
            println("no LEDs matched");
            return in;
        }

        float[] norm = new float[6];
        norm = getMinMaxCoords(getLEDVectors(in));

        ArrayList<LED> out = in;
        int index = 0;

        for (LED temp : out) {
            // Ignore 0,0 coordinates
            if (temp.coord.x > 0 && temp.coord.y > 0) {
                if (temp.coord.z != 0) {
                    // 3D coords
                    temp.coord.set(map(temp.coord.x, norm[0], norm[3], 0.001f, 1), map(temp.coord.y, norm[1], norm[4], 0.001f, 1), map(temp.coord.z, norm[2], norm[5], 0.001f, 1));
                    out.set(index, temp);
                } else {
                    // 2D coords
                    temp.coord.set(map(temp.coord.x, norm[0], norm[3], 0.001f, 1), map(temp.coord.y, norm[1], norm[4], 0.001f, 1));
                    out.set(index, temp);
                }
            }
            index++;
        }

        return out;
    }

// -----------------------------------------------------------
// -----------------------------------------------------------
// Utility methods

    //used to thread camera initialization. USB enumeration can be slow, and if it exceeds 5seconds the app will fail at startup.
    public void setupCam() {
        cam = new Capture(this, camWidth, camHeight, 30);
    }

    public void saveSVG(ArrayList<PVector> points) {
        if (points.size() == 0) {
            // User is trying to save without anything to output - bail
            println("No point data to save, run mapping first");
            return;
        } else {
            beginRecord(SVG, savePath);
            for (PVector p : points) {
                point(p.x, p.y);
            }
            endRecord();
            println("SVG saved");
        }
    }

    public void saveCSV(ArrayList<LED> ledArray, String path) {
        PrintWriter output;
        output = createWriter(path);

        //write vals out to file, start with csv header
        output.println("address" + "," + "x" + "," + "y" + "," + "z");

        for (int i = 0; i < ledArray.size(); i++) {
            output.println(ledArray.get(i).address + "," + ledArray.get(i).coord.x + "," + ledArray.get(i).coord.y + "," + ledArray.get(i).coord.z);
        }
        output.close(); // Finishes the file
        println("Exported CSV File to " + path);
    }

    // Console warranty  and OS info
    public void warranty() {
        println("Lightwork-Mapper");
        println("Copyright (C) 2017  Leó Stefánsson and Tim Rolls @PWRFL");
        println("This program comes with ABSOLUTELY NO WARRANTY");
        println("");
        String os = System.getProperty("os.name");
        println("Operating System: " + os);
    }

    // Close connections (once deployed as applet)
    public void stop() {
        cam = null;
        super.stop();
    }

    // Closes connections
    public void exit() {
        cam = null;
        super.exit();
    }
    /*         //<>// //<>// //<>//
     *  Animator
     *
     *  This Class handles timing and generates the state and color of all connected LEDs
     *
     *  Copyright (C) 2017 PWRFL
     *
     *  @authors Leó Stefánsson and Tim Rolls
     */


    enum AnimationMode {
        CHASE, TEST, BINARY, OFF
    }

    ;

    public class Animator {

        int ledIndex;               // Index of LED being mapped (lit and detected).
        int testIndex;              // Used for the test() animation sequence
        int frameCounter;           // Internal framecounter
        int frameSkip;              // How many frames to skip between updates

        AnimationMode mode;

        Animator() {
            ledIndex = 0; // Internal counter
            mode = AnimationMode.OFF;
            testIndex = 0;
            frameCounter = 0;
            this.frameSkip = 3;
            println("Animator created");
        }

        //TODO: additional constructors to set variables more clearly

        //////////////////////////////////////////////////////////////
        // Setters and getters
        //////////////////////////////////////////////////////////////


        public void setMode(AnimationMode m) {
            setAllLEDColours(off);
            mode = m;
            ledIndex = 0; // TODO: resetInternalVariables() method?
            testIndex = 0;
            frameCounter = 0;
        }

        public AnimationMode getMode() {
            return mode;
        }

        public void setLedBrightness(int brightness) { //TODO: set overall brightness?
            ledBrightness = brightness;
        }

        public void setFrameSkip(int num) {
            this.frameSkip = num;
        }

        public int getFrameSkip() {
            return this.frameSkip;
        }

        public int getLedIndex() {
            return ledIndex;
        }

        // Internal method to reassign pixels with a vector of the right length. Gives all pixels a value of (0,0,0) (black/off).
        public void resetPixels() {
            println("Animator -> resetPixels()");
            network.populateLeds();
            network.update(this.getPixels());
        }

        // Return pixels (to update OPC or PixelPusher) --Changed to array, arraylist wasn't working on return
        public int[] getPixels() {
            int[] l = new int[leds.size()];
            for (int i = 0; i < leds.size(); i++) {
                l[i] = leds.get(i).c;
            }
            return l;
        }


        //////////////////////////////////////////////////////////////
        // Animation Methods
        //////////////////////////////////////////////////////////////

        public void update() {

            switch (mode) {
                case CHASE: {
                    chase();
                    break;
                }
                case TEST: {
                    test();
                    break;
                }
                case BINARY: {
                    binaryAnimation();
                    break;
                }

                case OFF: {
                }
            }
            ;

            // Advance the internal counter
            frameCounter++;

            // Send pixel data over network
            if (mode != AnimationMode.OFF && network.isConnected) {
                network.update(this.getPixels());
            }
        }

        // Update the pixels for all the strips
        // This method does not return the pixels, it's up to the users to send animator.pixels to the driver (FadeCandy, PixelPusher).
        public void chase() {
            for (int i = 0; i < leds.size(); i++) {
                int col;
                if (i == ledIndex) {
                    col = color(ledBrightness, ledBrightness, ledBrightness);
                } else {
                    col = color(0, 0, 0);
                }
                leds.get(i).setColor(col);
            }

            if (frameCounter == 0) return; // Avoid the first LED going off too quickly //<>//
            if (frameCounter % this.frameSkip == 0) ledIndex++; // use frameskip to delay animation updates //<>//

            // Stop at end of LEDs
            if (ledIndex >= leds.size()) {
                this.setMode(AnimationMode.OFF);
            }
        }

        // Set all LEDs to the same colour (useful to turn them all on or off).
        public void setAllLEDColours(int col) {
            for (int i = 0; i < leds.size(); i++) {
                leds.get(i).setColor(col);
            }
        }

        // LED pre-flight test. Cycle: White, Red, Green, Blue.
        public void test() {
            testIndex++;

            if (testIndex < 30) {
                setAllLEDColours(color(ledBrightness, ledBrightness, ledBrightness));
            } else if (testIndex > 30 && testIndex < 60) {
                setAllLEDColours(color(ledBrightness, 0, 0));
            } else if (testIndex > 60 && testIndex < 90) {
                setAllLEDColours(color(0, ledBrightness, 0));
            } else if (testIndex > 90 && testIndex < 120) {
                setAllLEDColours(color(0, 0, ledBrightness));
            }

            if (testIndex > 120) {
                testIndex = 0;
            }
        }

        public void binaryAnimation() {
            if (frameCounter % this.frameSkip == 0) {
                for (int i = 0; i < leds.size(); i++) {
                    leds.get(i).binaryPattern.advance();

                    switch (leds.get(i).binaryPattern.state) {
                        case 0:
                            leds.get(i).setColor(color(0, 0, 0));
                            break;
                        case 1:
                            leds.get(i).setColor(color(ledBrightness, ledBrightness, ledBrightness));
                            //leds.get(i).setColor(color(ledBrightness, 0, 0)); //red for mapping can help in daylight
                            break;
                    }
                }
            }
        }
    }
    /*
     *  BinaryPattern Generator Class
     *
     *  This class generates binary patterns used in matching LED addressed to physical locations
     *
     *  Copyright (C) 2017 PWRFL
     *
     *  @authors Leó Stefánsson and Tim Rolls
     */

    public class BinaryPattern {

        // Pattern detection
        int previousState;
        int detectedState;
        int state; // Current bit state, used by animator
        int animationPatternLength; // 10 bit pattern with a START at the end and an OFF after each one
        int readIndex; // For reading bit at index (for the animation)
        int numBits;

        StringBuffer decodedString;
        int writeIndex; // For writing detected bits

        String binaryPatternString;
        int[] binaryPatternVector;
        String animationPatternString;
        int[] animationPatternVector;

        int frameNum;

        // Constructor
        BinaryPattern() {
            numBits = 10;
            animationPatternLength = 10;
            frameNum = 0;   // Used for animation
            readIndex = 0;  // Used by the detector to read bits
            writeIndex = 0; // Used by the detector to write bits
            previousState = 0;
            detectedState = 0;

            // TODO: Review initial capacity
            decodedString = new StringBuffer(10); // Init with capacity
            decodedString.append("W123456789");

            binaryPatternVector = new int[numBits];
            binaryPatternString = "";
            animationPatternVector = new int[animationPatternLength];
            animationPatternString = "";
        }

        // Generate Binary patterns for animation sequence and pattern-matching
        public void generatePattern(int num) {
            // Convert int to String of fixed length
            String s = Integer.toBinaryString(num);
            // TODO: string format, use numBits instead of hardcoded 10
            s = String.format("%10s", s).replace(" ", "0"); // Insert leading zeros to maintain pattern length
            binaryPatternString = s;

            // Convert Binary String to Vector of Ints
            for (int i = 0; i < binaryPatternVector.length; i++) {
                char c = binaryPatternString.charAt(i);
                int x = Character.getNumericValue(c);
                binaryPatternVector[i] = x;
            }
        }

        public void advance() {
            state = binaryPatternVector[frameNum];
            frameNum = frameNum + 1;
            if (frameNum >= animationPatternLength) {
                frameNum = 0;
            }
        }

        // Pattern storage
        public void writeNextBit(int bit) {
            String s = String.valueOf(bit);
            decodedString.replace(this.writeIndex, this.writeIndex + 1, s);

            this.writeIndex++;
            if (writeIndex >= animationPatternLength) {
                writeIndex = 0;
            }
        }

    }
    /*
     *  Blob Class
     *
     *  Based on this example by Daniel Shiffman:
     *  http://shiffman.net/2011/04/26/opencv-matching-faces-over-time/
     *
     *  @author: Jordi Tost (@jorditost)
     *
     *  University of Applied Sciences Potsdam, 2014
     *
     *  Modified by Leó Stefánsson
     */

    class Blob {

        private PApplet parent;

        // Contour
        public Contour contour;

        // Am I available to be matched?
        public boolean available;

        // How long should I live if I have disappeared?
        public int timer;

        // Unique ID for each blob
        int id;

        // Pattern Detection
        BinaryPattern detectedPattern;
        int brightness;
        int previousFrameCount; // FrameCount when last edge was detected


        // Make me
        Blob(PApplet parent, int id, Contour c) {
            this.parent = parent;
            this.id = id;
            this.contour = new Contour(parent, c.pointMat);
            this.available = true;
            this.timer = blobManager.lifetime; // TODO: Synchronize with Blob class and/or UI

            detectedPattern = new BinaryPattern();
            brightness = 0;
            previousFrameCount = 0;
        }

        // Show me
        public void display() {
            Rectangle r = contour.getBoundingBox();

            //set draw location based on displayed camera position, accounts for moving cam in UI
            float x = map(r.x, 0, (float) camWidth, (float) camArea.x, camArea.x + camArea.width);
            float y = map(r.y, 0, (float) camHeight, (float) camArea.y, camArea.y + camArea.height);

            noFill();
            stroke(255, 0, 0);
            rect(x, y, r.width, r.height);
        }

        public void update(Contour newContour) {
            this.contour = newContour;
        }

        // Count me down, I am gone
        public void countDown() {
            timer--;
        }

        // I am dead, delete me
        public boolean dead() {
            if (timer < 0) return true;
            return false;
        }

        public Rectangle getBoundingBox() {
            return contour.getBoundingBox();
        }

        // Decode Binary Pattern
        public void decode(int br) {
            brightness = br;
            int threshold = 25;
            int bit = 0;
            // Edge detection (rising/falling);
            if (brightness >= threshold) {
                bit = 1;
            } else if (brightness < threshold) {
                bit = 0;
            }
            // Write the detected bit to pattern
            detectedPattern.writeNextBit(bit);
        }
    }
    /*
     *  Blob Manager
     *
     *  This class manages blobs used to detect locations and patterns
     *
     *  Copyright (C) 2017 PWRFL
     *
     *  @authors Leó Stefánsson and Tim Rolls
     */

    class BlobManager {

        private PApplet parent;

        OpenCV contourFinder;

        int minBlobSize = 5;
        int maxBlobSize = 30;
        float distanceThreshold = 2;
        int lifetime = 200;

        // List of detected contours parsed as blobs (every frame)
        ArrayList<Contour> newBlobs;
        // List of my blob objects (persistent)
        ArrayList<Blob> blobList;
        // Number of blobs detected over all time. Used to set IDs.
        int blobCount = 0; // Use this to assign new (unique) ID's to blobs

        BlobManager(PApplet parent, OpenCV cv) {
            this.parent = parent;
            blobList = new ArrayList<Blob>();
            contourFinder = new OpenCV(this.parent, createImage(camWidth, camHeight, RGB));
        }

        public void update(PImage cvOutput) {
            contourFinder.loadImage(cvOutput);

            // Filter contours, remove contours that are too big or too small
            // The filtered results are our 'Blobs' (Should be detected LEDs)
            ArrayList<Contour> newBlobs = filterContours(contourFinder.findContours()); // Stores all blobs found in this frame

            // Note: newBlobs is of the Contours type
            // Register all the new blobs if the blobList is empty
            if (blobList.isEmpty()) {
                //println("Blob List is Empty, adding " + newBlobs.size() + " new blobs.");
                for (int i = 0; i < newBlobs.size(); i++) {
                    //println("+++ New blob detected with ID: " + blobCount);
                    int id = blobCount;
                    blobList.add(new Blob(parent, id, newBlobs.get(i)));
                    blobCount++;
                }
            }

            // Check if newBlobs are actually new...
            // First, check if the location is unique, so we don't register new blobs with the same (or similar) coordinates
            else {
                // New blobs must be further away to qualify as new blobs
                // Store new, qualified blobs found in this frame

                // Go through all the new blobs and check if they match an existing blob
                for (int i = 0; i < newBlobs.size(); i++) {
                    PVector p = new PVector(); // New blob center coord
                    Contour c = newBlobs.get(i);
                    // Get the center coordinate for the new blob
                    float x = (float) c.getBoundingBox().getCenterX();
                    float y = (float) c.getBoundingBox().getCenterY();
                    p.set(x, y);

                    // Check if an existing blob is under the distance threshold
                    // If it is under the threshold it is the 'same' blob
                    boolean didMatch = false;
                    for (int j = 0; j < blobList.size(); j++) {
                        Blob blob = blobList.get(j);
                        // Get existing blob coord
                        PVector p2 = new PVector();
                        p2.x = (float) blob.contour.getBoundingBox().getCenterX();
                        p2.y = (float) blob.contour.getBoundingBox().getCenterY();

                        float distance = p.dist(p2);
                        if (distance <= distanceThreshold) {
                            didMatch = true;
                            // New blob (c) is the same as old blob (blobList.get(j))
                            // Update old blob with new contour
                            blobList.get(j).update(c);
                            break;
                        }
                    }

                    // If none of the existing blobs are too close, add this one to the blob list
                    if (!didMatch) {
                        Blob b = new Blob(parent, blobCount, c);
                        blobCount++;
                        blobList.add(b);
                    }
                    // If new blob isTooClose to a a previous blob, reset the age.
                }
            }

            // Update the blob age
            // TODO: Reverse iteration
            for (int i = 0; i < blobList.size(); i++) {
                Blob b = blobList.get(i);
                b.countDown();
                if (b.dead()) {
                    blobList.remove(i); // TODO: Is this safe? Removing from array I'm iterating over...
                }
            }
        }

        public void display() {
            for (Blob b : blobList) {
                strokeWeight(1);
                b.display();
            }
        }

        public void setBlobLifetime(int lt) {
            lifetime = lt;
            println("blob lifetime: " + this.lifetime);
            for (Blob b : blobList) {
                b.timer = lt; // TODO: None of the blobs exist when I set the bloblifetime
            }
        }

        public void clearAllBlobs() {
            blobList.clear();
        }

        public int numBlobs() {
            return blobList.size();
        }

        // Filter out contours that are too small or too big
        public ArrayList<Contour> filterContours(ArrayList<Contour> newContours) {

            ArrayList<Contour> blobs = new ArrayList<Contour>();

            // Which of these contours are blobs?
            for (int i = 0; i < newContours.size(); i++) {

                Contour contour = newContours.get(i);
                Rectangle r = contour.getBoundingBox();

                // If contour is too small, don't add blob
                if (r.width < minBlobSize || r.height < minBlobSize || r.width > maxBlobSize || r.height > maxBlobSize) {
                    continue;
                }
                blobs.add(contour);
            }

            return blobs;
        }
    }
    /*   //<>//
     *  Interface
     *
     *  This class handles connecting to and switching between PixelPusher, FadeCandy, ArtNet and sACN devices.
     *
     *  Copyright (C) 2017 PWRFL
     *
     *  @authors Leó Stefánsson and Tim Rolls
     */


//Pixel Pusher library imports


// ArtNet


//sACN


//OSC


    enum device {
        FADECANDY, PIXELPUSHER, ARTNET, SACN, NULL
    }

    ;

    public class Interface {

        device mode;

        //LED defaults
        String IP = "fade2.local";
        int port = 7890;
        int ledsPerStrip = 64;
        int numStrips = 8;
        int numLeds = ledsPerStrip * numStrips;
        int ledBrightness;

        byte artnetPacket[];
        int numArtnetChannels = 3; // Channels per ArtNet fixture
        int numArtnetFixtures = 400; // Number of ArtNet DMX fixtures (each one can have multiple channels and LEDs)
        int numArtnetUniverses = 1; // Set in populateLeds
        int dmxUniverseSize = 512; // Could be used to implement short universes.

        boolean isConnected = false;
        boolean scraperActive = true;

        // Pixelpusher objects
        DeviceRegistry registry;
        TestObserver testObserver;

        // Fadecandy Objects
        OPC opc;

        // ArtNet objects
        ArtnetP5 artnet;

        //sACN objects
        sACNSource source;
        // sACNUniverse universe1;
        ArrayList<sACNUniverse> sacnUniverses = new ArrayList<sACNUniverse>();

        //OSC objects
        OscP5 oscP5;
        NetAddress myRemoteLocation;

        //////////////////////////////////////////////////////////////
        // Constructors
        /////////////////////////////////////////////////////////////

        //blank constructor to allow GUI setup
        Interface() {
            mode = device.NULL;
            populateLeds();
            setupOSC();
            println("Interface created");
        }

        //TODO: additional constructors to set variables more clearly

        // setup for Fadecandy
        Interface(device m, String ip, int strips, int leds) {
            mode = m;
            IP = ip;
            numStrips = strips;
            ledsPerStrip = leds;
            numLeds = ledsPerStrip * numStrips;
            populateLeds();
            println("Fadecandy Interface created");
        }

        // Setup for PixelPusher(no address required)
        Interface(device m, int strips, int leds) {
            mode = m;
            if (mode == device.PIXELPUSHER) {
                numStrips = strips;
                ledsPerStrip = leds;
                numLeds = ledsPerStrip * numStrips;
            }

            populateLeds();
            println("PixelPusher Interface created");
        }

        // Setup ArtNet / sACN (uses network discovery/multicast so no ip required)
        Interface(device m, int universes, int numFixtures, int numChans) {
            mode = m;
            if (mode == device.ARTNET || mode == device.SACN) {
                numArtnetFixtures = numFixtures;
                numArtnetChannels = numChans; // Number of channels per fixture
                println("Set num of unicerses " + universes);
                numArtnetUniverses = universes; // TODO: support more than one universe
            }

            populateLeds();
            println("ArtNet/sACN Interface created");
        }


        //////////////////////////////////////////////////////////////
        // Setters / getters and utility methods
        //////////////////////////////////////////////////////////////

        public void setMode(device m) {
            shutdown();
            mode = m;
        }

        public device getMode() {
            return mode;
        }

        public void setNumLedsPerStrip(int num) {
            ledsPerStrip = num;
            numLeds = ledsPerStrip * numStrips;
            populateLeds();
        }

        public int getNumLedsPerStrip() {
            return ledsPerStrip;
        }

        public void setNumStrips(int num) {
            numStrips = num;
            numLeds = ledsPerStrip * numStrips;
            populateLeds();
        }

        public int getNumStrips() {
            return numStrips;
        }

        public int getNumArtnetFixtures() {
            return numArtnetFixtures;
        }

        public void setNumArtnetFixtures(int numFixtures) {
            numArtnetFixtures = numFixtures;
            populateLeds();
        }

        public int getNumArtnetChannels() {
            return numArtnetChannels;
        }

        public void setNumArtnetChannels(int numChannels) {
            numArtnetChannels = numChannels;
            populateLeds();
        }


        public void setLedBrightness(int brightness) { //TODO: set overall brightness?
            ledBrightness = brightness;

            if (mode == device.PIXELPUSHER && isConnected()) {
                registry.setOverallBrightnessScale(ledBrightness);
            }

            if (opc != null && opc.isConnected()) {
            }
        }

        public void setIP(String ip) {
            IP = ip;
        }

        public String getIP() {
            println(IP);
            return IP;
        }

        public void setInterpolation(boolean state) {
            if (mode == device.FADECANDY) {
                opc.setInterpolation(state);
            } else {
                println("Interpolation only supported for FADECANDY.");
            }
        }

        public void setDithering(boolean state) {
            if (mode == device.FADECANDY) {
                opc.setDithering(state);
                opc.setInterpolation(state);
            } else {
                println("Dithering only supported for FADECANDY.");
            }
        }

        public boolean isConnected() {
            return isConnected;
        }

        // Set number of strips and pixels based on pusher config - only pulling for one right now.
        public void fetchPPConfig() {
            if (mode == device.PIXELPUSHER && isConnected()) {
                List<PixelPusher> pps = registry.getPushers();
                for (PixelPusher pp : pps) {  //TODO: calculate total strips / LEDs here for multiple pushers
                    IP = pp.getIp().toString();
                    numStrips = pp.getNumberOfStrips();
                    ledsPerStrip = pp.getPixelsPerStrip();
                    numLeds = numStrips * ledsPerStrip;
                }
            }

            populateLeds();
        }

        // Reset the LED vector
        public void populateLeds() {
            int val = 0;

            // Deal with ArtNet vs. LED structure
            if (mode == device.ARTNET || mode == device.SACN) {
                val = getNumArtnetFixtures();
            } else {
                val = numLeds;
            }

            // Clear existing LEDs
            if (leds.size() > 0) {
                println("Clearing LED Array");
                leds.clear();
                println("Turning off physical LEDs");
                network.clearLeds();
            }

            // Create new LEDS
            println("Creating LED Array");
            for (int i = 0; i < val; i++) {
                LED temp = new LED();
                leds.add(temp);
                leds.get(i).setAddress(i);
            }

            numLeds = leds.size();
            numArtnetUniverses = (int) Math.ceil(val * numArtnetChannels / (double) dmxUniverseSize);
            println("Setting num of universes " + numArtnetUniverses + " " + val + " " + numArtnetChannels + " " + dmxUniverseSize);
            sacnUniverses.clear();
            println("Num artnet " + (numArtnetUniverses + 1));
            for (int index = 0; index < numArtnetUniverses; index++) {
                println("Adding universe " + (short) (index + 1));
                sacnUniverses.add(new sACNUniverse(source, (short) (index + 1)));
            }
        }

        //set up OSC here to make constructors cleaner
        public void setupOSC() {
            oscP5 = new OscP5(this, 12001); // Listening on port 12001
            myRemoteLocation = new NetAddress("127.0.0.1", 12000); // sending over port 12000 to localhost
            //oscP5.plug(this, "toggleScraper", "/toggleScraper");
            //oscP5.plug(this, "newFile", "/newFile");
        }

        //////////////////////////////////////////////////////////////
        // Network Methods
        //////////////////////////////////////////////////////////////

        public void update(int[] colors) {

            switch (mode) {
                case FADECANDY: {
                    // Check if OPC object exists and is connected before writing data
                    if (opc != null && opc.isConnected()) {
                        opc.autoWriteData(colors);
                    }
                    break;
                }
                case PIXELPUSHER: {
                    // Check if network observer exists and has discovered strips before writing data
                    if (testObserver != null && testObserver.hasStrips) {
                        registry.startPushing();

                        // Iterate through PixelPusher strip objects to set LED colors
                        List<Strip> strips = registry.getStrips();
                        if (strips.size() > 0) {
                            int stripNum = 0;
                            for (Strip strip : strips) {
                                for (int stripPos = 0; stripPos < strip.getLength(); stripPos++) {
                                    int c = colors[(ledsPerStrip * stripNum) + stripPos];

                                    strip.setPixel(c, stripPos);
                                }
                                stripNum++;
                            }
                        }
                    }

                    break;
                }

                case ARTNET: {
                    // Grab all the colors
                    for (int i = 0; i < colors.length; i++) {
                        // Extract RGB values
                        // We assume the first three channels are RGB, and the rest is WHITE.
                        int r = (colors[i] >> 16) & 0xFF;  // Faster way of getting red(argb)
                        int g = (colors[i] >> 8) & 0xFF;   // Faster way of getting green(argb)
                        int b = colors[i] & 0xFF;          // Faster way of getting blue(argb)

                        // Write RGB values to the packet
                        int index = i * numArtnetChannels;
                        artnetPacket[index] = PApplet.parseByte(r); // Red
                        artnetPacket[index + 1] = PApplet.parseByte(g); // Green
                        artnetPacket[index + 2] = PApplet.parseByte(b); // Blue

                        // Populate remaining channels (presumably W) with color brightness
                        for (int j = 3; j < numArtnetChannels; j++) {
                            int br = PApplet.parseInt(brightness(colors[i]));
                            artnetPacket[index + j] = PApplet.parseByte(br); // White
                        }
                    }

                    artnet.broadcast(artnetPacket);

                    break;
                }

                case SACN: {
                    int colorIndex = 0;


                    StringBuilder colorString = new StringBuilder();
                    for (int i = 0; i < colors.length; i++) {
                        colorString.append(colors[i]).append(",");
                    }

                    println(colorString.toString());
                    byte[] fakeArtnetPacket = new byte[numArtnetChannels * numArtnetFixtures];
                    byte[][] fakeArtnet2d = new byte[numArtnetUniverses][512];
                    for (int i = 0; i < colors.length; i++) {
                        // Extract RGB values
                        // We assume the first three channels are RGB, and the rest is WHITE.
                        int r = (colors[i] >> 16) & 0xFF;  // Faster way of getting red(argb)
                        int g = (colors[i] >> 8) & 0xFF;   // Faster way of getting green(argb)
                        int b = colors[i] & 0xFF;          // Faster way of getting blue(argb)

                        // Write RGB values to the packet
                        int index = i * numArtnetChannels;
                        fakeArtnetPacket[index] = PApplet.parseByte(r); // Red
                        fakeArtnetPacket[index + 1] = PApplet.parseByte(g); // Green
                        fakeArtnetPacket[index + 2] = PApplet.parseByte(b); // Blue

                        // Populate remaining channels (presumably W) with color brightness
                        for (int j = 0; j < numArtnetChannels; j++) {
                            if(index>505){
                                println("test");
                            }
                            if(index>1025){
                                println("test");
                            }
                            int br = PApplet.parseInt(brightness(colors[i]));
                            fakeArtnetPacket[index + j] = PApplet.parseByte(br); // White
                            fakeArtnet2d[(int) Math.floor((index+j) / 512.0)][(index + j)% 512] = fakeArtnetPacket[index + j];
                        }
                    }


                    // Iterate by dmx address rather than color.
                    println("Num universes " + numArtnetUniverses);
                    for (int universe = 0; universe < numArtnetUniverses; universe++) {
                        int offset = universe * dmxUniverseSize;
                        int length = Math.min(dmxUniverseSize, colors.length * numArtnetChannels - offset);
                        int channel = 0;
                        println(channel + "Channel");
                        int index = 0;
                        for (; index < length; index++) {
                            artnetPacket[index] = fakeArtnet2d[universe][index];
                        }

                        //fill rest of universe with 0's
                        for (; index < 512; index++) {
                            artnetPacket[index] = 0;
                        }


                        // for (int i = 0; i < colors.length; i++) {
                        //   // Extract RGB values
                        //   // We assume the first three channels are RGB, and the rest is WHITE.
                        //   int r =
                        //   int g = (colors[i] >> 8) & 0xFF;   // Faster way of getting green(argb)
                        //   int b = colors[i] & 0xFF;          // Faster way of getting blue(argb)

                        //   // Write RGB values to the packet
                        //   int index = i*numArtnetChannels;
                        //   artnetPacket[index]   = byte(r); // Red
                        //   artnetPacket[index+1] = byte(g); // Green
                        //   artnetPacket[index+2] = byte(b); // Blue

                        //
                        //   for (int j = 3; j < numArtnetChannels; j++) {
                        //     int br = int(brightness(colors[i]));
                        //     artnetPacket[index+j] = byte(br); // White
                        //   }
                        // }


                        //slots can add channel offset to the beginning of the packet
                        sACNUniverse universeOb = sacnUniverses.get(universe);
                        println("Sending universe " + universe);
                        universeOb.setSlots(0, artnetPacket);

                        try {
                            universeOb.sendData();
                        } catch (Exception e) {
                            e.printStackTrace();
                            exit();
                        }
                    }
                    break;
                }

                case NULL: {
                    break;
                }
            }
            ;
        }

        public void clearLeds() {
            int valCount = 0;

            // Deal with ArtNet vs. LED addresses
            if (mode == device.ARTNET || mode == device.SACN) {
                valCount = numArtnetFixtures;
            } else {
                valCount = numLeds;
            }
            int[] col = new int[valCount];
            for (int c : col) {
                c = color(0);
            }

            if (isConnected) {
                update(col); // Update Physical LEDs with black (off)
            }
        }


        // Open Connection to Controller
        public void connect(PApplet parent) {
            populateLeds(); //rebuild LED vector - helps avoid out of bounds errors

            if (isConnected) {
                shutdown();
            } else if (mode == device.FADECANDY) {
                if (opc == null || !opc.isConnected) {
                    opc = new OPC(parent, IP, port);
                    int startTime = millis();

                    print("waiting");
                    while (!opc.isConnected()) {
                        int currentTime = millis();
                        int deltaTime = currentTime - startTime;
                        if ((deltaTime % 1000) == 0) {
                            print(".");
                        }
                        if (deltaTime > 5000) {
                            println(" ");
                            println("connection failed, check your connections...");
                            isConnected = false;
                            network.shutdown();
                            break;
                        }
                    }
                    println(" ");
                }

                if (opc.isConnected()) {
                    // TODO: Find a more elegant way to initialize dithering
                    // Currently this is the only safe place where this is guaranteed to work
                    //opc.setDithering(false);
                    //opc.setInterpolation(false);
                    // TODO: Deal with this (doesn't work for FUTURE wall, works fine on LIGHT WORK wall).

                    // Clear LEDs
                    animator.setAllLEDColours(off);
                    // Update pixels twice (elegant, I know... but it works)
                    update(animator.getPixels());
                    println("Connected to Fadecandy OPC server at: " + IP + ":" + port);
                    isConnected = true;
                    opc.setPixelCount(numLeds);
                    populateLeds();
                }
            } else if (mode == device.PIXELPUSHER) {
                // Does not like being instantiated a second time
                if (registry == null) {
                    registry = new DeviceRegistry();
                    testObserver = new TestObserver();
                }


                registry.addObserver(testObserver);
                registry.setAntiLog(true);
                //Prevents PP from spamming the console
                registry.setLogging(false);

                int startTime = millis();

                //Test for connection
                print("waiting");
                while (!testObserver.hasStrips) {
                    int currentTime = millis();
                    int deltaTime = currentTime - startTime;
                    if ((deltaTime % 1000) == 0) {
                        print(".");
                    }
                    if (deltaTime > 5000) {
                        println(" ");
                        println("connection failed, check your connections...");
                        isConnected = false;
                        break;
                    }
                }
                println(" ");

                //Setup on connection
                if (testObserver.hasStrips) {
                    fetchPPConfig();
                    isConnected = true;

                    // Clear LEDs
                    animator.setAllLEDColours(off);
                    update(animator.getPixels());

                    populateLeds();
                }
            } else if (mode == device.ARTNET) {
                artnet = new ArtnetP5();
                isConnected = true;
                artnetPacket = new byte[numArtnetChannels * numArtnetFixtures];
            } else if (mode == device.SACN) {
                source = new sACNSource(parent, "LightWork");
                sacnUniverses.clear();
                println("Num artnet " + (numArtnetUniverses + 1));
                for (int index = 0; index < numArtnetUniverses; index++) {
                    println("Adding universe " + (short) (index + 1));
                    sacnUniverses.add(new sACNUniverse(source, (short) (index + 1)));
                }
                isConnected = true;
                //artnetPacket = new byte[numArtnetChannels*numArtnetFixtures];
                artnetPacket = new byte[512]; //size for full universe, helps make sure additional addresses get 0 values
            }
        }

        // Close existing connections
        public void shutdown() {
            if (mode == device.FADECANDY && opc != null) {
                opc.dispose();
                isConnected = false;
            }
            if (mode == device.PIXELPUSHER && registry != null) {
                registry.stopPushing();  //TODO: Need to disconnect devices as well
                registry.deleteObserver(testObserver);
                isConnected = false;
            }
            if (mode == device.ARTNET) {
                // TODO: deinitialize artnet connection,library keeps looking for nodes - no visible stop methods
                artnet = null;
                isConnected = false;
            }
            if (mode == device.SACN) {
                source = null;
                sacnUniverses.clear();
                isConnected = false;
            }
            if (mode == device.NULL) {
            }
        }


        // Toggle verbose logging for PixelPusher
        public void pusherLogging(boolean b) {
            registry.setLogging(b);
        }

        // Send osc to local scraper, toggling sending data to LEDs. This allows us to quickly switch from mapping to scraping.
        public void oscToggleScraper() {
            scraperActive = !scraperActive;
            OscMessage myMessage = new OscMessage("/toggleScraper");
            myMessage.add(PApplet.parseInt(scraperActive));
            oscP5.send(myMessage, myRemoteLocation);
        }

        public void oscNewFile() {
            OscMessage myMessage = new OscMessage("/newFile");
            myMessage.add(1);
            oscP5.send(myMessage, myRemoteLocation);
        }

        public void saveOSC(ArrayList<LED> ledArray) {

            //write vals out to file, start with csv header
            //output.println("address"+","+"x"+","+"y"+","+"z");

            //ledArray=normCoords(ledArray); //normalize before sending

            for (int i = 0; i < ledArray.size(); i++) {
                OscMessage myMessage = new OscMessage("/coords");
                myMessage.add(ledArray.get(i).address)
                        .add(ledArray.size()) //include array size so scraper knows total numbers of LEDs
                        .add(ledArray.get(i).coord.x)
                        .add(ledArray.get(i).coord.y)
                        .add(ledArray.get(i).coord.z)
                ;
                oscP5.send(myMessage, myRemoteLocation);
            }


            println("Exported coords to scraper via OSC");
        }
    }

// PixelPusher Observer
// Monitors network for changes in PixelPusher configuration

    class TestObserver implements Observer {
        public boolean hasStrips = false;

        public void update(Observable registry, Object updatedDevice) {
            println("Registry changed!");
            if (updatedDevice != null) {
                println("Device change: " + updatedDevice);
            }
            this.hasStrips = true;
        }
    }

    public void delayThread(int ms) {
        try {
            Thread.sleep(ms);
        } catch (Exception e) {
        }
    }
    /*
     *  Keypress
     *
     *  Keyboard event handlers, mostly duplicate functionality with the GUI
     *
     *  Copyright (C) 2017 PWRFL
     *
     *  @authors Leó Stefánsson and Tim Rolls
     */

// Shortcuts:
// ALT-SHIFT-S : Save UI properties to file
// ALT-SHIFT-L : Load UI properties to file
// SHIFT-H : Disable dragging (if accidentally activated with shift)

    public void keyPressed() {
        //if (key == 's') {
        //  if (leds.size() == 0) { // TODO: Review this
        //    //User is trying to save without anything to output - bail
        //    println("No point data to save, run mapping first");
        //    return;
        //  } else {
        //    File sketch = new File(sketchPath());
        //    selectOutput("Select a file to write to:", "fileSelected", sketch);
        //    saveCSV(leds, savePath);
        //  }
        //}

        if (key == 'm') {
            backgroundImage = videoInput.copy();
            animator.setFrameSkip(6);
            if (network.isConnected() == false) {
                println("please connect to a device before mapping");
            } else if (animator.getMode() != AnimationMode.CHASE) {
                animator.setMode(AnimationMode.CHASE);

                println("Chase mode");
            } else {
                animator.setMode(AnimationMode.OFF);
                println("Animator off");
            }
            blobManager.setBlobLifetime(animator.frameSkip); // Make sure there's only one blob at a time.
            isMapping = !isMapping;
        }

        // Export binary image (CV FBO)
        if (key == 'p') {
            cvFBO.save("future_binary_close.png");
        }

        // Capture Image sequence
        // When we are done capturing an image sequence, switch to videoMode = VideoMode.IMAGE_SEQUENCE
        //if (key == 'i') {
        //  binaryMapping();
        //}
        //// (K)Calibration Mode
        //if (key == 'k') {
        //  calibrate();
        //}
        //// print led info
        //if (key == 'l') {

        //  // Save to the Scraper project
        //  String savePath = "../LightWork_Scraper/data/layout.csv";
        //  saveCSV(leds, savePath);
        //}
        //if (key == 't') {
        //  if (network.isConnected()==false) {
        //    println("please connect to a device before testing");
        //  } else if (animator.getMode()!=AnimationMode.TEST) {
        //    animator.setMode(AnimationMode.TEST);
        //    println("Test mode");
        //  } else {
        //    animator.setMode(AnimationMode.OFF);
        //    println("Animator off");
        //  }
        //}

        //if (key == 'b') {
        //  if (animator.getMode()!=AnimationMode.BINARY) {
        //    animator.setMode(AnimationMode.BINARY);
        //    println("Binary mode (monochrome)");
        //  } else {
        //    animator.setMode(AnimationMode.OFF);
        //    println("Animator off");
        //  }
        //}

        //// Test connecting to OPC server
        //if (key == 'o') {
        //  network.setMode(device.FADECANDY);
        //  network.connect(this);
        //}

        //// Test connecting to PP
        //if (key == 'p') {
        //  network.setMode(device.PIXELPUSHER);
        //  network.connect(this);
        //}

        //// All LEDs Black (clear)
        //if (key == 'c') {
        //  for (int i = 0; i < leds.size(); i++) {
        //    leds.get(i).coord.set(0, 0);
        //  }
        //}

        //// All LEDs White (clear)
        //if (key == 'w') {
        //  if (network.isConnected()) {
        //    animator.setAllLEDColours(on);
        //    animator.update();
        //  }
        //}

        ////check led coords
        //if (key == 'l') {
        //  for (LED led : leds) {
        //    println(led.coord);
        //  }
        //}
    }
    /*
     *  LED
     *
     *  This class tracks location, pattern and color data for an LED object
     *
     *  Copyright (C) 2017 PWRFL
     *
     *  @authors Leó Stefánsson and Tim Rolls
     */

    public class LED {
        int c; // Current LED color
        int address; // LED Address
        int bPatternOffset; // Offset the Binary Pattern Seed to avoid 0000000000
        BinaryPattern binaryPattern;
        PVector coord;
        boolean foundMatch;

        LED() {
            c = color(0, 0, 0);
            address = 0;
            coord = new PVector(0, 0);
            bPatternOffset = 682;
            binaryPattern = new BinaryPattern();
            foundMatch = false;
        }

        public void setColor(int col) {
            c = col;
        }

        // Set LED address and generate a unique binary pattern
        public void setAddress(int addr) {
            address = addr;
            binaryPattern.generatePattern(address + bPatternOffset);
        }

        public void setCoord(PVector coordinates) {
            coord.set(coordinates.x, coordinates.y);
        }
    }
    /*
     * Simple Open Pixel Control client for Processing,
     * designed to sample each LED's color from some point on the canvas.
     *
     * Micah Elizabeth Scott, 2013
     * This file is released into the public domain.
     */


    public class OPC implements Runnable {
        Thread thread;
        Socket socket;
        OutputStream output, pending;
        String host;
        int port;

        int[] pixelLocations;
        byte[] packetData;
        byte firmwareConfig;
        String colorCorrection;
        boolean enableShowLocations;
        boolean isConnected;

        OPC(PApplet parent, String host, int port) {
            isConnected = false;
            this.host = host;
            this.port = port;
            thread = new Thread(this);
            thread.start();
            this.enableShowLocations = true;
            parent.registerMethod("draw", this);
        }

        // Set the location of a single LED
        public void led(int index, int x, int y) {
            // For convenience, automatically grow the pixelLocations array. We do want this to be an array,
            // instead of a HashMap, to keep draw() as fast as it can be.
            if (pixelLocations == null) {
                pixelLocations = new int[index + 1];
            } else if (index >= pixelLocations.length) {
                pixelLocations = Arrays.copyOf(pixelLocations, index + 1);
            }

            pixelLocations[index] = x + width * y;
        }

        // Set the location of several LEDs arranged in a strip.
        // Angle is in radians, measured clockwise from +X.
        // (x,y) is the center of the strip.
        public void ledStrip(int index, int count, float x, float y, float spacing, float angle, boolean reversed) {
            float s = sin(angle);
            float c = cos(angle);
            for (int i = 0; i < count; i++) {
                led(reversed ? (index + count - 1 - i) : (index + i),
                        (int) (x + (i - (count - 1) / 2.0f) * spacing * c + 0.5f),
                        (int) (y + (i - (count - 1) / 2.0f) * spacing * s + 0.5f));
            }
        }

        // Set the locations of a ring of LEDs. The center of the ring is at (x, y),
        // with "radius" pixels between the center and each LED. The first LED is at
        // the indicated angle, in radians, measured clockwise from +X.
        public void ledRing(int index, int count, float x, float y, float radius, float angle) {
            for (int i = 0; i < count; i++) {
                float a = angle + i * 2 * PI / count;
                led(index + i, (int) (x - radius * cos(a) + 0.5f),
                        (int) (y - radius * sin(a) + 0.5f));
            }
        }

        // Set the location of several LEDs arranged in a grid. The first strip is
        // at 'angle', measured in radians clockwise from +X.
        // (x,y) is the center of the grid.
        public void ledGrid(int index, int stripLength, int numStrips, float x, float y,
                            float ledSpacing, float stripSpacing, float angle, boolean zigzag) {
            float s = sin(angle + HALF_PI);
            float c = cos(angle + HALF_PI);
            for (int i = 0; i < numStrips; i++) {
                ledStrip(index + stripLength * i, stripLength,
                        x + (i - (numStrips - 1) / 2.0f) * stripSpacing * c,
                        y + (i - (numStrips - 1) / 2.0f) * stripSpacing * s, ledSpacing,
                        angle, zigzag && (i % 2) == 1);
            }
        }

        // Set the location of 64 LEDs arranged in a uniform 8x8 grid.
        // (x,y) is the center of the grid.
        public void ledGrid8x8(int index, float x, float y, float spacing, float angle, boolean zigzag) {
            ledGrid(index, 8, 8, x, y, spacing, spacing, angle, zigzag);
        }

        public void ledGrid5x10(int index, float x, float y, float spacing, float angle, boolean zigzag) {
            ledGrid(index, 10, 5, x, y, spacing, spacing, angle, zigzag);
        }

        // Should the pixel sampling locations be visible? This helps with debugging.
        // Showing locations is enabled by default. You might need to disable it if our drawing
        // is interfering with your processing sketch, or if you'd simply like the screen to be
        // less cluttered.
        public void showLocations(boolean enabled) {
            enableShowLocations = enabled;
        }

        // Enable or disable dithering. Dithering avoids the "stair-stepping" artifact and increases color
        // resolution by quickly jittering between adjacent 8-bit brightness levels about 400 times a second.
        // Dithering is on by default.
        public void setDithering(boolean enabled) {
            if (enabled)
                firmwareConfig &= ~0x01;
            else
                firmwareConfig |= 0x01;
            sendFirmwareConfigPacket();
        }

        // Enable or disable frame interpolation. Interpolation automatically blends between consecutive frames
        // in hardware, and it does so with 16-bit per channel resolution. Combined with dithering, this helps make
        // fades very smooth. Interpolation is on by default.
        public void setInterpolation(boolean enabled) {
            if (enabled)
                firmwareConfig &= ~0x02;
            else
                firmwareConfig |= 0x02;
            sendFirmwareConfigPacket();
        }

        // Put the Fadecandy onboard LED under automatic control. It blinks any time the firmware processes a packet.
        // This is the default configuration for the LED.
        public void statusLedAuto() {
            firmwareConfig &= 0x0C;
            sendFirmwareConfigPacket();
        }

        // Manually turn the Fadecandy onboard LED on or off. This disables automatic LED control.
        public void setStatusLed(boolean on) {
            firmwareConfig |= 0x04;   // Manual LED control
            if (on)
                firmwareConfig |= 0x08;
            else
                firmwareConfig &= ~0x08;
            sendFirmwareConfigPacket();
        }

        // Set the color correction parameters
        public void setColorCorrection(float gamma, float red, float green, float blue) {
            colorCorrection = "{ \"gamma\": " + gamma + ", \"whitepoint\": [" + red + "," + green + "," + blue + "]}";
            sendColorCorrectionPacket();
        }

        // Set custom color correction parameters from a string
        public void setColorCorrection(String s) {
            colorCorrection = s;
            sendColorCorrectionPacket();
        }

        // Send a packet with the current firmware configuration settings
        public void sendFirmwareConfigPacket() {
            if (pending == null) {
                // We'll do this when we reconnect
                return;
            }

            byte[] packet = new byte[9];
            packet[0] = (byte) 0x00; // Channel (reserved)
            packet[1] = (byte) 0xFF; // Command (System Exclusive)
            packet[2] = (byte) 0x00; // Length high byte
            packet[3] = (byte) 0x05; // Length low byte
            packet[4] = (byte) 0x00; // System ID high byte
            packet[5] = (byte) 0x01; // System ID low byte
            packet[6] = (byte) 0x00; // Command ID high byte
            packet[7] = (byte) 0x02; // Command ID low byte
            packet[8] = (byte) firmwareConfig;

            try {
                pending.write(packet);
            } catch (Exception e) {
                dispose();
            }
        }

        // Send a packet with the current color correction settings
        public void sendColorCorrectionPacket() {
            if (colorCorrection == null) {
                // No color correction defined
                return;
            }
            if (pending == null) {
                // We'll do this when we reconnect
                return;
            }

            byte[] content = colorCorrection.getBytes();
            int packetLen = content.length + 4;
            byte[] header = new byte[8];
            header[0] = (byte) 0x00;               // Channel (reserved)
            header[1] = (byte) 0xFF;               // Command (System Exclusive)
            header[2] = (byte) (packetLen >> 8);   // Length high byte
            header[3] = (byte) (packetLen & 0xFF); // Length low byte
            header[4] = (byte) 0x00;               // System ID high byte
            header[5] = (byte) 0x01;               // System ID low byte
            header[6] = (byte) 0x00;               // Command ID high byte
            header[7] = (byte) 0x01;               // Command ID low byte

            try {
                pending.write(header);
                pending.write(content);
            } catch (Exception e) {
                dispose();
            }
        }

        // Automatically called at the end of each draw().
        // This handles the automatic Pixel to LED mapping.
        // If you aren't using that mapping, this function has no effect.
        // In that case, you can call setPixelCount(), setPixel(), and writePixels()
        // separately.
        public void draw() {
            if (pixelLocations == null) {
                // No pixels defined yet
                return;
            }
            if (output == null) {
                return;
            }

            int numPixels = pixelLocations.length;
            int ledAddress = 4;

            //setPixelCount(numPixels);
            loadPixels();

            for (int i = 0; i < numPixels; i++) {
                int pixelLocation = pixelLocations[i];
                int pixel = pixels[pixelLocation];

                packetData[ledAddress] = (byte) (pixel >> 16);
                packetData[ledAddress + 1] = (byte) (pixel >> 8);
                packetData[ledAddress + 2] = (byte) pixel;
                ledAddress += 3;

                if (enableShowLocations) {
                    pixels[pixelLocation] = 0xFFFFFF ^ pixel;
                }
            }

            writePixels();

            if (enableShowLocations) {
                updatePixels();
            }
        }

        // Change the number of pixels in our output packet.
        // This is normally not needed; the output packet is automatically sized
        // by draw() and by setPixel().
        public void setPixelCount(int numPixels) {
            //println("setPixelCount: " + numPixels);
            int numBytes = 3 * numPixels;
            int packetLen = 4 + numBytes;
            if (packetData == null || packetData.length != packetLen) {
                // Set up our packet buffer
                packetData = new byte[packetLen];
                packetData[0] = (byte) 0x00;              // Channel
                packetData[1] = (byte) 0x00;              // Command (Set pixel colors)
                packetData[2] = (byte) (numBytes >> 8);   // Length high byte
                packetData[3] = (byte) (numBytes & 0xFF); // Length low byte
            }
        }

        // Directly manipulate a pixel in the output buffer. This isn't needed
        // for pixels that are mapped to the screen.
        public void setPixel(int number, int c) {
            int offset = 4 + number * 3;
            if (packetData == null || packetData.length < offset + 3) {
                setPixelCount(number + 1);
            }

            packetData[offset] = (byte) (c >> 16);
            packetData[offset + 1] = (byte) (c >> 8);
            packetData[offset + 2] = (byte) c;
        }

        // Read a pixel from the output buffer. If the pixel was mapped to the display,
        // this returns the value we captured on the previous frame.
        public int getPixel(int number) {
            int offset = 4 + number * 3;
            if (packetData == null || packetData.length < offset + 3) {
                return 0;
            }
            return (packetData[offset] << 16) | (packetData[offset + 1] << 8) | packetData[offset + 2];
        }

        // Transmit our current buffer of pixel values to the OPC server. This is handled
        // automatically in draw() if any pixels are mapped to the screen, but if you haven't
        // mapped any pixels to the screen you'll want to call this directly.
        public void writePixels() {
            if (packetData == null || packetData.length == 0) {
                // No pixel buffer
                return;
            }
            if (output == null) {
                return;
            }

            try {
                output.write(packetData);
            } catch (Exception e) {
                dispose();
            }
        }

        public void dispose() {
            // Destroy the socket. Called internally when we've disconnected.
            // (Thread continues to run)
            if (output != null) {
                println("Disconnected from OPC server");
            }
            socket = null;
            output = pending = null;
        }

        public void run() {
            // Thread tests server connection periodically, attempts reconnection.
            // Important for OPC arrays; faster startup, client continues
            // to run smoothly when mobile servers go in and out of range.
            for (; ; ) {

                if (output == null) { // No OPC connection?
                    try {              // Make one!
                        socket = new Socket(host, port);
                        socket.setTcpNoDelay(true);
                        pending = socket.getOutputStream(); // Avoid race condition...
                        println("Connected to OPC server");
                        isConnected = true;
                        sendColorCorrectionPacket();        // These write to 'pending'
                        sendFirmwareConfigPacket();         // rather than 'output' before
                        output = pending;                   // rest of code given access.
                        // pending not set null, more config packets are OK!
                    } catch (ConnectException e) {
                        dispose();
                    } catch (IOException e) {
                        dispose();
                    }
                }

                // Pause thread to avoid massive CPU load
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                }
            }
        }

        //--------------------------------------------------------------
        // Additions to OPC class
        //--------------------------------------------------------------

        int ledsPerStrip;

        public boolean isConnected() {
            return isConnected;
        }

        public void autoWriteData(int[] pix) // TODO: test this, it's hacked together
        {
            // Bail early if there's no pixel data or there is too much data
            if (pix.length == 0) {
                println("No Data");
                return;
            }

            for (int i = 0; i < pix.length; i++) {
                int c = pix[i];

                int offset = 4 + i * 3;
                if (packetData == null || packetData.length < offset + 3) {
                    setPixelCount(i + 1);
                }

                packetData[offset] = (byte) (c >> 16);
                packetData[offset + 1] = (byte) (c >> 8);
                packetData[offset + 2] = (byte) c;
            }

            writePixels();
        }

    }
    /*
     *  UI
     *
     *  This class builds the UI for the application
     *
     *  Copyright (C) 2017 PWRFL
     *
     *  @authors Leó Stefánsson and Tim Rolls
     */


    Textarea cp5Console;
    Println console;
    RadioButton r1, r2;
    Range blob;
    Textlabel tl1;
//ControlFrame cf;

    boolean isUIReady = false;
    boolean showLEDColors = true;
    boolean patternMapping = true;
    boolean stereoMode = false;
    boolean mapRight = false;

    //Window size
    int windowSizeX, windowSizeY;
    int guiMultiply = 1;

    // Actual display size for camera
    int camDisplayWidth, camDisplayHeight;
    Rectangle camArea;

    int frameSkip = 20;

    public void buildUI() {
        println("setting up ControlP5");

        cp5 = new ControlP5(this);
        cp5.setVisible(false);
        cp5.enableShortcuts();

        // Check for defaults file
        File defaults = new File("controlP5.json");

        float startTime = millis();
        println("Building UI... at time: " + startTime);
        int uiGrid = 60 * guiMultiply;
        int uiSpacing = 20 * guiMultiply;
        int buttonHeight = 30 * guiMultiply;
        int buttonWidth = 150 * guiMultiply;
        int topBarHeight = 70 * guiMultiply;

        println("Creating font...");
        PFont pfont = createFont("OpenSans-Regular.ttf", 12 * guiMultiply, false); // use true/false for smooth/no-smooth
        ControlFont font = new ControlFont(pfont, 12 * guiMultiply);
        cp5.setFont(font);
        cp5.setColorBackground(0xff333333);
        cp5.setPosition(uiSpacing, uiSpacing);

        cp5.mapKeyFor(new ControlKey() {
                          public void keyEvent() {
                          }
                      }
                , ALT);


        Group top = cp5.addGroup("top")
                .setPosition(0, 0)
                .setBackgroundHeight(30)
                .setWidth(width - uiSpacing * 2)
                //.setBackgroundColor(color(255, 50))
                //.disableCollapse()
                .hideBar();

        Group net = cp5.addGroup("network")
                .setPosition(0, (topBarHeight) + camDisplayHeight)
                .setBackgroundHeight(200)
                .setWidth(uiGrid * 4)
                //.setBackgroundColor(color(255, 50))
                .hideBar();

        Group settings = cp5.addGroup("settings")
                .setPosition((uiGrid + uiSpacing) * 4, (topBarHeight) + camDisplayHeight)
                .setBackgroundHeight(200)
                .setWidth(uiGrid * 4)
                //.setBackgroundColor(color(255, 50))
                .hideBar();

        Group mapping = cp5.addGroup("mapping")
                .setPosition((uiGrid + uiSpacing) * 8, (topBarHeight) + camDisplayHeight)
                .setBackgroundHeight(200)
                .setWidth(uiGrid * 4)
                //.setBackgroundColor(color(255, 50))
                .hideBar();

        Group buttons = cp5.addGroup("buttons")
                .setPosition(-uiSpacing, height - topBarHeight)
                .setBackgroundHeight(topBarHeight)
                .setWidth(width)
                .setBackgroundColor(color(70))
                .hideBar();

        // loadWidth = width/12*6;
        println("adding textfield for IP");
        cp5.addTextfield("ip")
                .setCaptionLabel("ip address")
                .setPosition(0, buttonHeight + uiSpacing)
                .setSize(buttonWidth, buttonHeight)
                .setAutoClear(false)
                .setGroup("network")
                .setValue(network.getIP())
                .setVisible(false)
                .getCaptionLabel().align(ControlP5.RIGHT_OUTSIDE, CENTER).setPadding(5 * guiMultiply, 5 * guiMultiply)
        ;

        println("adding textfield for ledsPerStrip");
        cp5.addTextfield("leds_per_strip")
                .setCaptionLabel("leds per strip")
                .setPosition(0, buttonHeight * 2 + uiSpacing * 2)
                .setSize(buttonWidth, buttonHeight)
                .setAutoClear(false)
                .setGroup("network")
                .setValue(str(network.getNumLedsPerStrip()))
                .setVisible(false)
                .getCaptionLabel().align(ControlP5.RIGHT_OUTSIDE, CENTER).setPadding(5 * guiMultiply, 5 * guiMultiply)
        ;

        println("adding textfield for strips");
        cp5.addTextfield("strips")
                .setPosition(0, buttonHeight * 3 + uiSpacing * 3)
                .setSize(buttonWidth, buttonHeight)
                .setAutoClear(false)
                .setGroup("network")
                .setValue(str(network.getNumStrips()))
                .setVisible(false)
                .getCaptionLabel().align(ControlP5.RIGHT_OUTSIDE, CENTER).setPadding(5 * guiMultiply, 5 * guiMultiply)
        ;

        println("adding textfield for number of DMX/ArtNet fixtures");
        cp5.addTextfield("fixtures")
                .setPosition(0, buttonHeight * 3 + uiSpacing * 3)
                .setSize(buttonWidth, buttonHeight)
                .setAutoClear(false)
                .setGroup("network")
                .setValue(str(network.getNumArtnetFixtures()))
                .setVisible(false)
                .getCaptionLabel().align(ControlP5.RIGHT_OUTSIDE, CENTER).setPadding(5 * guiMultiply, 5 * guiMultiply)
        ;

        println("adding textfield for number of number of channels per DMX/Artnet Fixture");
        cp5.addTextfield("channels")
                .setPosition(0, buttonHeight * 2 + uiSpacing * 2)
                .setSize(buttonWidth, buttonHeight)
                .setAutoClear(false)
                .setGroup("network")
                .setValue(str(network.getNumArtnetChannels()))
                .setVisible(false)
                .getCaptionLabel().align(ControlP5.RIGHT_OUTSIDE, CENTER).setPadding(5 * guiMultiply, 5 * guiMultiply)
        ;


        println("listing drivers");
        //draw after text boxes so the dropdown overlaps properly
        List driver = Arrays.asList("PixelPusher", "Fadecandy", "ArtNet", "sACN");
        println("adding scrollable list for drivers");
        cp5.addScrollableList("driver")
                .setPosition(0, 0)
                .setSize(PApplet.parseInt(buttonWidth * 1.5f), 300)
                .setBarHeight(buttonHeight)
                .setItemHeight(buttonHeight)
                .addItems(driver)
                .setType(ControlP5.DROPDOWN)
                .setOpen(false)
                .bringToFront()
                .setGroup("network");
        ;
        // TODO:  fix style on dropdown
        cp5.getController("driver").getCaptionLabel().align(ControlP5.CENTER, ControlP5.CENTER).setPaddingX(uiSpacing);

        println("adding connect button");
        cp5.addButton("connect")
                .setPosition(uiSpacing, uiSpacing / 2)
                .setSize(buttonWidth / 2 - 2, buttonHeight)
                .setGroup("buttons");
        ;

        println("adding contrast slider");
        cp5.addSlider("cvContrast")
                .setCaptionLabel("contrast")
                .setBroadcast(false)
                .setPosition(0, 0)
                .setSize(buttonWidth, buttonHeight)
                .setRange(0, 5)
                .setValue(cvContrast)
                .setGroup("settings")
                .setMoveable(false)
                .setBroadcast(true)
                .getCaptionLabel().align(ControlP5.RIGHT_OUTSIDE, CENTER).setPadding(5 * guiMultiply, 5 * guiMultiply)
        ;


        println("adding slider for cvThreshold");
        cp5.addSlider("cvThreshold")
                .setCaptionLabel("threshold")
                .setBroadcast(false)
                .setPosition(0, buttonHeight + uiSpacing)
                .setSize(buttonWidth, buttonHeight)
                .setRange(0, 255)
                .setValue(cvThreshold)
                .setGroup("settings")
                .setBroadcast(true)
                .getCaptionLabel().align(ControlP5.RIGHT_OUTSIDE, CENTER).setPadding(5 * guiMultiply, 5 * guiMultiply)

        ;

        ////set labels to bottom
        //cp5.getController("cvThreshold").getValueLabel().align(ControlP5.RIGHT, ControlP5.BOTTOM_OUTSIDE).setPaddingX(0);
        //cp5.getController("cvThreshold").getCaptionLabel().align(ControlP5.LEFT, ControlP5.BOTTOM_OUTSIDE).setPaddingX(0);

        println("adding slider for ledBrightness");
        cp5.addSlider("ledBrightness")
                .setCaptionLabel("led brightness")
                .setBroadcast(false)
                .setPosition(0, (buttonHeight + uiSpacing) * 2)
                .setSize(buttonWidth, buttonHeight)
                .setRange(0, 255)
                .setValue(ledBrightness)
                .setGroup("settings")
                //.plugTo(ledBrightness)
                .setBroadcast(true)
                .getCaptionLabel().align(ControlP5.RIGHT_OUTSIDE, CENTER).setPadding(5 * guiMultiply, 5 * guiMultiply)
        ;

        //// Set labels to bottom
        //cp5.getController("ledBrightness").getValueLabel().align(ControlP5.RIGHT, ControlP5.BOTTOM_OUTSIDE).setPaddingX(0);
        //cp5.getController("ledBrightness").getCaptionLabel().align(ControlP5.LEFT, ControlP5.BOTTOM_OUTSIDE).setPaddingX(0);

        // Mapping type toggle
        List mapToggle = Arrays.asList("Pattern", "Sequence");
        ButtonBar b = cp5.addButtonBar("mappingToggle")
                .setPosition(0, (buttonHeight + uiSpacing) * 3)
                .setSize(buttonWidth, buttonHeight)
                .addItems(mapToggle)
                .setDefaultValue(0)
                //.setId(0)
                .setGroup("settings");

        tl1 = cp5.addTextlabel("mapMode")
                .setText("MAPPING MODE")
                .setPosition(buttonWidth + 5, 5 * guiMultiply + (buttonHeight + uiSpacing) * 3)
                .setGroup("settings")
                .setFont(font)
        ;

        println("adding test button");
        cp5.addButton("calibrate")
                .setPosition((uiGrid + uiSpacing) * 4 + uiSpacing, uiSpacing / 2)
                .setSize(buttonWidth / 2 - 2, buttonHeight)
                .setGroup("buttons")
        ;

        println("adding map button");
        cp5.addButton("map")
                .setPosition((uiGrid + uiSpacing) * 4 + (buttonWidth / 2) + 2 + uiSpacing, uiSpacing / 2)
                .setSize(buttonWidth / 2 - 2, buttonHeight)
                .setGroup("buttons")
                .setCaptionLabel("map")
        ;

        cp5.addButton("map2")
                .setPosition((uiGrid + uiSpacing) * 4 + buttonWidth + 2 + uiSpacing, uiSpacing / 2)
                .setSize(buttonWidth / 2 - 2, buttonHeight)
                .setGroup("buttons")
                .setCaptionLabel("map right")
                .setVisible(false)
        ;

        println("adding save button");
        cp5.addButton("saveLayout")
                .setCaptionLabel("Save Layout")
                .setPosition((uiGrid + uiSpacing) * 8 + uiSpacing, uiSpacing / 2)
                .setSize(PApplet.parseInt(buttonWidth * .75f), buttonHeight)
                .setGroup("buttons")
        ;

        //println("adding settings button");
        //cp5.addButton("saveSettings")
        //  .setCaptionLabel("Save Settings")
        //  .setPosition((uiGrid+uiSpacing)*8+uiSpacing+int(buttonWidth*.75)+4, uiSpacing/2)
        //  .setSize(int(buttonWidth*.75), buttonHeight)
        //  .setGroup("buttons")
        //  ;

        println("adding frameskip slider");
        cp5.addSlider("frameskip")
                .setBroadcast(false)
                .setPosition(0, 0)
                .setSize(buttonWidth, buttonHeight)
                .setRange(6, 30)
                .setValue(frameSkip)
                .plugTo(frameSkip)
                .setValue(12)
                .setGroup("mapping")
                .setBroadcast(true)
                .getCaptionLabel().align(ControlP5.RIGHT_OUTSIDE, CENTER).setPadding(5 * guiMultiply, 5 * guiMultiply)
        ;

        println("adding blob range slider");
        blob = cp5.addRange("blobSize")
                // disable broadcasting since setRange and setRangeValues will trigger an event
                .setBroadcast(false)
                .setCaptionLabel("min/max blob size")
                .setPosition(0, buttonHeight + uiSpacing)
                .setSize(buttonWidth, buttonHeight)
                .setHandleSize(10 * guiMultiply)
                .setRange(1, 100)
                .setRangeValues(blobManager.minBlobSize, blobManager.maxBlobSize)
                .setGroup("mapping")
                .setBroadcast(true)
        ;

        cp5.getController("blobSize").getCaptionLabel().align(ControlP5.RIGHT_OUTSIDE, CENTER).setPadding(5 * guiMultiply, 5 * guiMultiply);

        println("adding blob distance slider");
        cp5.addSlider("setBlobDistanceThreshold")
                .setBroadcast(false)
                .setCaptionLabel("min blob distance")
                .setPosition(0, (buttonHeight + uiSpacing) * 2)
                .setSize(buttonWidth, buttonHeight)
                .setValue(4)
                .setRange(1, 10)
                .plugTo(blobManager.distanceThreshold)
                .setGroup("mapping")
                .setBroadcast(true)
                .getCaptionLabel().align(ControlP5.RIGHT_OUTSIDE, CENTER).setPadding(5 * guiMultiply, 5 * guiMultiply)
        ;

        println("add framerate panel");
        cp5.addFrameRate().setPosition((camDisplayWidth * 2) - uiSpacing * 3, 0);

        cp5.addToggle("stereoToggle")
                .setBroadcast(false)
                .setCaptionLabel("Stereo Toggle")
                .setPosition((buttonWidth * 2) + uiSpacing * 3, 0)
                .setSize(buttonWidth / 3, buttonHeight)
                .setGroup("top")
                .setValue(true)
                .setMode(ControlP5.SWITCH)
                .setBroadcast(true)
                .getCaptionLabel().align(ControlP5.RIGHT_OUTSIDE, CENTER).setPadding(5, 5)
        ;

        // Refresh connected cameras
        println("cp5: adding refresh button");
        cp5.addButton("refresh")
                .setPosition(PApplet.parseInt(buttonWidth * 1.5f) + uiSpacing, 0)
                .setSize(buttonWidth / 2, buttonHeight)
                .setGroup("top")
        ;

        println("Enumerating cameras");
        String[] cams = enumerateCams();
        // made last - enumerating cams will break the ui if done earlier in the sequence
        println("cp5: adding camera dropdown list");
        cp5.addScrollableList("camera")
                .setPosition(0, 0)
                .setSize(PApplet.parseInt(buttonWidth * 1.5f), 300)
                .setBarHeight(buttonHeight)
                .setItemHeight(buttonHeight)
                .addItems(cams)
                .setOpen(false)
                .setGroup("top")
        ;

        //cp5.getController("camera").getCaptionLabel().align(ControlP5.CENTER, CENTER).setPadding(10*guiMultiply, 5*guiMultiply);

        // Load defaults
        if (defaults.exists()) {
            cp5.loadProperties("controlP5.json");
            cp5.update();
        }

        addMouseWheelListener();

        // Wrap up, report done
        // loadWidth = width;
        float deltaTime = millis() - startTime;
        println("Done building GUI, total time: " + deltaTime + " ms");
        cp5.setVisible(true);
        isUIReady = true;
    }

//////////////////////////////////////////////////////////////
// Event Handlers
//////////////////////////////////////////////////////////////

    public void camera(int n) {
        Map m = cp5.get(ScrollableList.class, "camera").getItem(n);
        //println(m);
        String label = m.get("name").toString();
        //println(label);
        switchCamera(label);
    }


    public void driver(int n) {
        String label = cp5.get(ScrollableList.class, "driver").getItem(n).get("name").toString().toUpperCase();

        if (label.equals("PIXELPUSHER")) {
            //handles switching to another driver while connected
            if (network.isConnected) {
                network.shutdown();
                cp5.get("connect").setCaptionLabel("Connect");
            }
            network.setMode(device.PIXELPUSHER);
            //network.fetchPPConfig();
            cp5.get(Textfield.class, "ip").setVisible(false);
            cp5.get(Textfield.class, "leds_per_strip").setVisible(false);
            cp5.get(Textfield.class, "strips").setVisible(false);
            cp5.get(Textfield.class, "fixtures").setVisible(false);
            cp5.get(Textfield.class, "channels").setVisible(false);
            println("network: PixelPusher");
        } else if (label.equals("FADECANDY")) {
            //handles switching to another driver while connected
            if (network.isConnected) {
                network.shutdown();
                cp5.get("connect").setCaptionLabel("Connect");
            }
            network.setMode(device.FADECANDY);
            cp5.get(Textfield.class, "ip").setVisible(true);
            cp5.get(Textfield.class, "leds_per_strip").setVisible(true);
            cp5.get(Textfield.class, "strips").setVisible(true);
            cp5.get(Textfield.class, "fixtures").setVisible(false);
            cp5.get(Textfield.class, "channels").setVisible(false);
            println("network: Fadecandy");
        } else if (label.equals("ARTNET")) {
            //handles switching to another driver while connected
            if (network.isConnected) {
                network.shutdown();
                cp5.get("connect").setCaptionLabel("Connect");
            }
            network.setMode(device.ARTNET);
            cp5.get(Textfield.class, "ip").setVisible(false);
            cp5.get(Textfield.class, "fixtures").setVisible(true);
            cp5.get(Textfield.class, "channels").setVisible(true);
            cp5.get(Textfield.class, "leds_per_strip").setVisible(false);
            cp5.get(Textfield.class, "strips").setVisible(false);
            println("network: ArtNet");
        } else if (label.equals("SACN")) {
            //handles switching to another driver while connected
            if (network.isConnected) {
                network.shutdown();
                cp5.get("connect").setCaptionLabel("Connect");
            }
            network.setMode(device.SACN);
            cp5.get(Textfield.class, "ip").setVisible(false);
            cp5.get(Textfield.class, "fixtures").setVisible(true);
            cp5.get(Textfield.class, "channels").setVisible(true);
            cp5.get(Textfield.class, "leds_per_strip").setVisible(false);
            cp5.get(Textfield.class, "strips").setVisible(false);
            println("network: sACN");
        }
    }

    public void ip(String theText) {
        println("IP set to : " + theText);
        network.setIP(theText);
    }

    public void leds_per_strip(String theText) {
        println("Leds per strip set to : " + theText);
        network.setNumLedsPerStrip(PApplet.parseInt(theText));
    }

    public void strips(String theText) {
        println("Strips set to : " + theText);
        network.setNumStrips(PApplet.parseInt(theText));
    }

    public void fixtures(String numFixtures) {
        println("Fixtures set to: " + numFixtures);
        network.setNumArtnetFixtures(PApplet.parseInt(numFixtures));
    }

    public void channels(String numChannels) {
        println("DMX Channels per Fixture set to: " + numChannels);
        network.setNumArtnetChannels(PApplet.parseInt(numChannels));
    }

    public void connect() {

        //No driver selected
        if (network.getMode() != device.NULL) {
            network.connect(this);
        } else {
            println("Please select a driver type from the dropdown before attempting to connect");
        }

        //Fetch hardware configuration from PixelPusher
        if (network.getMode() == device.PIXELPUSHER && network.isConnected()) {
            network.fetchPPConfig();
            cp5.get(Textfield.class, "ip").setValue(network.getIP()).setVisible(true);
            cp5.get(Textfield.class, "leds_per_strip").setValue(str(network.getNumLedsPerStrip())).setVisible(true);
            cp5.get(Textfield.class, "strips").setValue(str(network.getNumStrips())).setVisible(true);
        }

        if (network.isConnected()) {
            cp5.get("connect").setColorBackground(0xff00aaff);
            cp5.get("connect").setCaptionLabel("Refresh");
        }
    }

    public void refresh() {
        String[] cameras = enumerateCams();
        cp5.get(ScrollableList.class, "camera").setItems(cameras);
    }

    public void cvThreshold(int value) {
        cvThreshold = value;
    }

    public void cvContrast(float value) {
        cvContrast = value;
    }

    public void ledBrightness(int value) {
        ledBrightness = value;
        animator.setLedBrightness(value);
    }

    public void frameskip(int value) {
        frameSkip = value;
        animator.setFrameSkip(value);
    }

    public void setBlobDistanceThreshold(float t) {
        blobManager.distanceThreshold = t;
    }

    public void controlEvent(ControlEvent theControlEvent) {
        if (theControlEvent.isFrom("blobSize")) {
            blobManager.minBlobSize = PApplet.parseInt(theControlEvent.getController().getArrayValue(0));
            blobManager.maxBlobSize = PApplet.parseInt(theControlEvent.getController().getArrayValue(1));
        }
    }

    public void calibrate() {
        if (network.isConnected() == false) {
            println("Please connect to an LED driver before calibrating");
            return;
        }
        if (cam == null || !cam.isLoaded()) {
            println("Please select a camera before calibrating");
            return;
        }
        // Activate Calibration Mode
        else if (videoMode != VideoMode.CALIBRATION) {
            network.oscToggleScraper();
            blobManager.setBlobLifetime(1000);
            videoMode = VideoMode.CALIBRATION;
            backgroundImage = videoInput.copy();
            backgroundImage.save("data/calibrationBackgroundImage.png");
            cp5.get("calibrate").setCaptionLabel("stop");
            cp5.get("calibrate").setColorBackground(0xff00aaff);
            if (patternMapping == true) {
                println("Calibration: pattern");
                animator.setMode(AnimationMode.BINARY);
            } else {
                println("Calibration: sequence");
                animator.setMode(AnimationMode.CHASE);
            }
        }
        // Decativate Calibration Mode
        else if (videoMode == VideoMode.CALIBRATION) {
            network.oscToggleScraper();
            blobManager.clearAllBlobs();
            videoMode = VideoMode.CAMERA;
            //backgroundImage = createImage(camWidth, camHeight, RGB);
            //opencv.loadImage(backgroundImage); // Clears OpenCV frame
            animator.setMode(AnimationMode.OFF);
            animator.resetPixels();
            cp5.get("calibrate").setCaptionLabel("calibrate");
            cp5.get("calibrate").setColorBackground(0xff333333);
            println("Calibration: off");
        }
    }

    public void saveLayout() {
        if (leds.size() <= 0) { // TODO: review, does this work?
            // User is trying to save without anything to output - bail
            println("No point data to save, run mapping first");
            return;
        } else if (stereoMode == true && leftMap != null && rightMap != null) {
            // Save stereo map with Z
            calculateZ(leftMap, rightMap);
            savePath = "../Lightwork_Scraper_3D/data/stereoLayout.csv";
            File sketch = new File(savePath);
            selectOutput("Select a file to write to:", "fileSelected", sketch);
        } else {
            // Save 2D Map
            File sketch = new File(savePath);
            selectOutput("Select a file to write to:", "fileSelected", sketch);
        }
    }

    // event handler for AWT file selection window
    public void fileSelected(File selection) {
        if (selection == null) {
            println("Window was closed or the user hit cancel.");
        } else {
            savePath = selection.getAbsolutePath();
            println("User selected " + selection.getAbsolutePath());
            saveCSV(normCoords(leds), savePath);
        }
    }

    // TODO: investigate "ignoring" error and why this doesn't work, but keypress do
    public void saveSettings(float v) {
        cp5.saveProperties("default");
    }

    public void stereoToggle(boolean theFlag) {
        if (theFlag == true) {
            stereoMode = false;
            cp5.get(Button.class, "map").setCaptionLabel("map");
            cp5.get(Button.class, "map2").setVisible(false);
            println("Stereo mode off");
        } else {
            stereoMode = true;
            cp5.get(Button.class, "map").setCaptionLabel("map left");
            cp5.get(Button.class, "map2").setVisible(true);
            println("Stereo mode on");
        }
    }

    public void mappingToggle(int n) {
        if (n == 0) {
            videoMode = VideoMode.IMAGE_SEQUENCE;
            patternMapping = true;
            println("Mapping Mode: Pattern");
        } else if (n == 1) {
            videoMode = VideoMode.CAMERA;
            patternMapping = false;
            println("Mapping Mode: Sequence");
        }
    }


    public void map() {
        if (network.isConnected() == false) {
            println("Please connect to an LED driver before mapping");
            return;
        }
        if (cam == null || !cam.isLoaded()) {
            println("Please select a camera before mapping");
            return;
        }
        // Turn off mapping
        else if (isMapping) {
            println("Mapping stopped");
            network.oscToggleScraper();
            videoMode = VideoMode.CAMERA;

            animator.setMode(AnimationMode.OFF);
            network.clearLeds();

            shouldStartPatternMatching = false;
            shouldStartDecoding = false;
            images.clear();
            currentFrame = 0;
            isMapping = false;
            cp5.get("map").setColorBackground(0xff333333);
            cp5.get("map").setCaptionLabel("map");
        }

        //Binary pattern mapping
        else if (!isMapping && patternMapping == true) {
            network.oscToggleScraper();
            println("Binary pattern mapping started");
            videoMode = VideoMode.IMAGE_SEQUENCE;

            backgroundImage = cam.copy();
            backgroundImage.save(dataPath("backgroundImage.png")); // Save background image for debugging purposes

            blobManager.clearAllBlobs();
            blobManager.setBlobLifetime(400); // TODO: Replace hardcoded 10 with binary pattern length

            animator.setMode(AnimationMode.BINARY);
            animator.resetPixels();

            currentFrame = 0; // Reset current image sequence index
            isMapping = true;
            cp5.get("map").setColorBackground(0xff00aaff);
            cp5.get("map").setCaptionLabel("Stop");
        }
        // Sequential Mapping
        else if (!isMapping && patternMapping == false) {
            network.oscToggleScraper();
            println("Sequential mapping started");
            blobManager.clearAllBlobs();
            videoMode = VideoMode.CAMERA;
            animator.setMode(AnimationMode.CHASE);
            backgroundImage = videoInput.copy();
            //animator.resetPixels();
            blobManager.setBlobLifetime(frameSkip * 20); // TODO: Replace 10 with binary pattern length
            isMapping = true;
            cp5.get("map").setColorBackground(0xff00aaff);
            cp5.get("map").setCaptionLabel("Stop");
        }
    }

    public void map2() {
        if (network.isConnected() == false) {
            println("Please connect to an LED driver before mapping");
            return;
        }

        if (cam == null || !cam.isLoaded()) {
            println("Please select a camera before mapping");
            return;
        }
        // Turn off mapping
        else if (isMapping) {
            println("Mapping stopped");
            videoMode = VideoMode.CAMERA;

            animator.setMode(AnimationMode.OFF);
            network.clearLeds();

            shouldStartPatternMatching = false;
            images.clear();
            currentFrame = 0;
            isMapping = false;
            cp5.get("map2").setColorBackground(0xff333333);
            cp5.get("map2").setCaptionLabel("Stop");
        }

        // Binary pattern mapping
        else if (!isMapping && patternMapping == true) {
            mapRight = true;
            println("Binary pattern mapping started");
            videoMode = VideoMode.IMAGE_SEQUENCE;

            backgroundImage = cam.copy();
            backgroundImage.save(dataPath("backgroundImage.png")); // Save background image for debugging purposes

            blobManager.clearAllBlobs();
            blobManager.setBlobLifetime(400); // TODO: Replace hardcoded 10 with binary pattern length

            animator.setMode(AnimationMode.BINARY);
            animator.resetPixels();

            currentFrame = 0; // Reset current image sequence index
            isMapping = true;
            cp5.get("map2").setColorBackground(0xff00aaff);
            cp5.get("map2").setCaptionLabel("Stop");
        }
        // Sequential Mapping
        else if (!isMapping && patternMapping == false) {
            println("Sequential mapping started");
            blobManager.clearAllBlobs();
            videoMode = VideoMode.CAMERA;
            animator.setMode(AnimationMode.CHASE);
            backgroundImage = videoInput.copy();

            blobManager.setBlobLifetime(400); // TODO: Review blob lifetime
            isMapping = true;
            cp5.get("map2").setColorBackground(0xff00aaff);
            cp5.get("map2").setCaptionLabel("Stop");
        }
    }

//////////////////////////////////////////////////////////////
// UI Methods
//////////////////////////////////////////////////////////////

    // Get the list of currently connected cameras
    public String[] enumerateCams() {

        String[] list = Capture.list();

        // Catch null cases
        if (list == null) {
            println("Failed to retrieve the list of available cameras, will try the default...");
            //cam = new Capture(this, camWidth, camHeight, FPS);
        } else if (list.length == 0) {
            println("There are no cameras available for capture.");
        }

        // Parse out camera names from device listing
        for (int i = 0; i < list.length; i++) {
            String item = list[i];
            String[] temp = splitTokens(item, ",=");
            list[i] = temp[1];
        }

        // This operation removes duplicates from the camera names, leaving only individual device names
        // the set format automatically removes duplicates without having to iterate through them
        Set<String> set = new HashSet<String>();
        Collections.addAll(set, list);
        String[] cameras = set.toArray(new String[0]);

        return cameras;
    }

    // UI camera switching - Cam 1
    public void switchCamera(String name) {
        if (cam != null) {
            cam.stop();
            cam = null;
        }
        cam = new Capture(this, camWidth, camHeight, name, 30);
        cam.start();
    }

    // Draw the array of colors going out to the LEDs
    public void showLEDOutput() {
        if (showLEDColors) {
            // Scale based on window size and leds in array
            float x = (float) width / (float) leds.size();
            for (int i = 0; i < leds.size(); i++) {
                fill(leds.get(i).c);
                noStroke();
                rect(i * x, (camArea.y + camArea.height) - (5), x, 5);
            }
        }
    }

    // Display feedback on how many blobs and LEDs have been detected
    public void showBlobCount() {
        fill(0, 255, 0);
        textAlign(LEFT);
        textSize(12 * guiMultiply);
        String blobTemp = "Blobs: " + blobManager.numBlobs();
        String ledTemp = "Matched LEDs: " + listMatchedLEDs();
        text(blobTemp, 20 * guiMultiply, 100 * guiMultiply);
        text(ledTemp, width / 2 + (20 * guiMultiply), 100 * guiMultiply);
    }

    // Loading screen
    public void loading() {
        background(0);
        if (frameCount % 1000 == 0) {
            println("DrawLoop: Building UI....");
        }

        int size = (millis() / 5 % 255);

        pushMatrix();
        translate(width / 2, height / 2);
        noFill();
        stroke(255, size);
        strokeWeight(4);
        ellipse(0, 0, size, size);
        translate(0, 200);
        fill(255);
        noStroke();
        textSize(18);
        textAlign(CENTER);
        text("LOADING...", 0, 0);
        popMatrix();
    }

    // Mousewheel support in Desktop mode
    public void addMouseWheelListener() {
        frame.addMouseWheelListener(new java.awt.event.MouseWheelListener() {
                                        public void mouseWheelMoved(java.awt.event.MouseWheelEvent e) {
                                            cp5.setMouseWheelRotation(e.getWheelRotation());
                                        }
                                    }
        );
    }

    public void window2d() {
        println("Setting window size");
        windowSizeX = 960 * guiMultiply;
        windowSizeY = 700 * guiMultiply; // adds to height for ui elements above and below cams
        surface.setSize(windowSizeX, windowSizeY);

        println("display: " + displayWidth + ", " + displayHeight + "  Window: " + width + ", " + height);

        surface.setLocation((displayWidth / 2) - width / 2, ((int) displayHeight / 2) - height / 2);

        camDisplayWidth = (int) (width / 2);
        camDisplayHeight = (int) (camDisplayWidth / camAspect);
        camArea = new Rectangle(0, 70 * guiMultiply, camDisplayWidth, camDisplayHeight);

        println("camDisplayWidth: " + camDisplayWidth);
        println("camDisplayHeight: " + camDisplayHeight);
        println("camArea.x: " + camArea.x + " camArea.y: " + camArea.y + " camArea.width: " + camArea.width + " camArea.height: " + camArea.height);
    }

    public void settings() {
        size(960, 700, P3D);
    }

    static public void main(String[] passedArgs) {
        String[] appletArgs = new String[]{"LightWork_Mapper"};
        if (passedArgs != null) {
            PApplet.main(concat(appletArgs, passedArgs));
        } else {
            PApplet.main(appletArgs);
        }
    }
}

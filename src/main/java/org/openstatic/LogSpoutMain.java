package org.openstatic;

import java.io.File;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.awt.AWTError;
import java.awt.Desktop;
import java.awt.GraphicsEnvironment;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.FileSystemException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.StringTokenizer;
import java.util.regex.Pattern;

import org.apache.commons.cli.*;
import org.json.*;
import org.openstatic.log.*;
import org.apache.commons.jexl3.JexlEngine;
import org.apache.commons.jexl3.JexlExpression;
import org.apache.commons.jexl3.JexlFeatures;
import org.apache.commons.jexl3.JexlBuilder;
import org.apache.commons.jexl3.JexlContext;
import org.apache.commons.jexl3.MapContext;

public class LogSpoutMain
{
    public static JSONObject settings;
    public static File configFile;
    public static JexlEngine jexl;
    public static boolean verbose = false;
    public static APIWebServer apiWebServer;
    private static JSONObject remotePresetOwner;
    private static JSONArray remotePresets = new JSONArray();
    private static final String logSpoutAsciiArt = "                                \n      \u001B[48;2;0;0;0m\u001B[38;2;0;0;0m\u2584\u001B[0m\u001B[48;2;0;0;0m\u001B[38;2;0;0;0m\u2584\u001B[0m\u001B[48;2;0;0;0m\u001B[38;2;229;182;36m\u2584\u001B[0m\u001B[48;2;0;0;0m\u001B[38;2;86;68;14m\u2584\u001B[0m\u001B[48;2;0;0;0m\u001B[38;2;0;0;0m\u2584\u001B[0m\u001B[38;2;0;0;0m\u2584\u001B[0m             \n \u001B[48;2;94;67;40m\u001B[38;2;0;0;0m\u2584\u001B[0m\u001B[48;2;155;110;66m\u001B[38;2;155;110;66m\u2584\u001B[0m\u001B[48;2;0;0;0m\u001B[38;2;0;0;0m\u2584\u001B[0m\u001B[48;2;155;110;66m\u001B[38;2;155;110;66m\u2584\u001B[0m\u001B[48;2;0;0;0m\u001B[38;2;155;110;66m\u2584\u001B[0m\u001B[38;2;0;0;0m\u2584\u001B[0m            \n    \u001B[38;2;0;0;0m\u2584\u001B[0m\u001B[38;2;0;0;0m\u2584\u001B[0m\u001B[38;2;0;0;0m\u2584\u001B[0m\u001B[38;2;0;0;0m\u2584\u001B[0m\u001B[38;2;0;0;0m\u2584\u001B[0m\u001B[38;2;0;0;0m\u2584\u001B[0m\u001B[38;2;0;0;0m\u2584\u001B[0m\u001B[38;2;0;0;0m\u2584\u001B[0m\u001B[38;2;0;0;0m\u2584\u001B[0m\u001B[38;2;0;0;0m\u2584\u001B[0m\u001B[48;2;0;0;0m\u001B[38;2;0;0;0m\u2584\u001B[0m\u001B[48;2;155;110;66m\u001B[38;2;0;0;0m\u2584\u001B[0m\u001B[48;2;155;110;66m\u001B[38;2;155;110;66m\u2584\u001B[0m\u001B[48;2;0;0;0m\u001B[38;2;0;0;0m\u2584\u001B[0m\u001B[48;2;155;110;66m\u001B[38;2;155;110;66m\u2584\u001B[0m\u001B[48;2;155;110;66m\u001B[38;2;155;110;66m\u2584\u001B[0m\u001B[48;2;0;0;0m\u001B[38;2;0;0;0m\u2584\u001B[0m\u001B[38;2;0;0;0m\u2584\u001B[0m\u001B[38;2;0;0;0m\u2584\u001B[0m\u001B[38;2;155;110;66m\u2584\u001B[0m\u001B[38;2;2;1;1m\u2584\u001B[0m\u001B[38;2;0;0;0m\u2584\u001B[0m\u001B[38;2;0;0;0m\u2584\u001B[0m\u001B[38;2;0;0;0m\u2584\u001B[0m    \n  \u001B[38;2;0;0;0m\u2584\u001B[0m\u001B[48;2;0;0;0m\u001B[38;2;255;202;40m\u2584\u001B[0m\u001B[48;2;255;202;40m\u001B[38;2;255;202;40m\u2584\u001B[0m\u001B[48;2;255;202;40m\u001B[38;2;0;0;0m\u2584\u001B[0m\u001B[48;2;255;202;40m\u001B[38;2;6;5;1m\u2584\u001B[0m\u001B[48;2;255;202;40m\u001B[38;2;255;202;40m\u2584\u001B[0m\u001B[48;2;0;0;0m\u001B[38;2;255;202;40m\u2584\u001B[0m\u001B[48;2;155;110;66m\u001B[38;2;0;0;0m\u2584\u001B[0m\u001B[48;2;155;110;66m\u001B[38;2;155;110;66m\u2584\u001B[0m\u001B[48;2;155;110;66m\u001B[38;2;155;110;66m\u2584\u001B[0m\u001B[48;2;155;110;66m\u001B[38;2;88;63;38m\u2584\u001B[0m\u001B[48;2;155;110;66m\u001B[38;2;88;63;38m\u2584\u001B[0m\u001B[48;2;155;110;66m\u001B[38;2;88;63;38m\u2584\u001B[0m\u001B[48;2;155;110;66m\u001B[38;2;88;63;38m\u2584\u001B[0m\u001B[48;2;0;0;0m\u001B[38;2;155;110;66m\u2584\u001B[0m\u001B[48;2;0;0;0m\u001B[38;2;155;110;66m\u2584\u001B[0m\u001B[48;2;0;0;0m\u001B[38;2;155;110;66m\u2584\u001B[0m\u001B[48;2;0;0;0m\u001B[38;2;88;63;38m\u2584\u001B[0m\u001B[48;2;155;110;66m\u001B[38;2;102;73;44m\u2584\u001B[0m\u001B[48;2;155;110;66m\u001B[38;2;155;110;66m\u2584\u001B[0m\u001B[48;2;155;110;66m\u001B[38;2;155;110;66m\u2584\u001B[0m\u001B[48;2;155;110;66m\u001B[38;2;88;63;38m\u2584\u001B[0m\u001B[48;2;155;110;66m\u001B[38;2;88;63;38m\u2584\u001B[0m\u001B[48;2;155;110;66m\u001B[38;2;88;63;38m\u2584\u001B[0m\u001B[48;2;155;110;66m\u001B[38;2;88;63;38m\u2584\u001B[0m\u001B[48;2;155;110;66m\u001B[38;2;88;63;38m\u2584\u001B[0m\u001B[48;2;0;0;0m\u001B[38;2;88;63;38m\u2584\u001B[0m\u001B[38;2;0;0;0m\u2584\u001B[0m  \n  \u001B[48;2;0;0;0m\u001B[38;2;243;192;38m\u2584\u001B[0m\u001B[48;2;255;202;40m\u001B[38;2;255;202;40m\u2584\u001B[0m\u001B[48;2;0;0;0m\u001B[38;2;11;9;2m\u2584\u001B[0m\u001B[48;2;255;202;40m\u001B[38;2;255;202;40m\u2584\u001B[0m\u001B[48;2;255;202;40m\u001B[38;2;71;56;12m\u2584\u001B[0m\u001B[48;2;0;0;0m\u001B[38;2;255;202;40m\u2584\u001B[0m\u001B[48;2;255;202;40m\u001B[38;2;0;0;0m\u2584\u001B[0m\u001B[48;2;217;172;34m\u001B[38;2;255;202;40m\u2584\u001B[0m\u001B[48;2;109;77;46m\u001B[38;2;0;0;0m\u2584\u001B[0m\u001B[48;2;155;110;66m\u001B[38;2;155;110;66m\u2584\u001B[0m\u001B[48;2;155;110;66m\u001B[38;2;155;110;66m\u2584\u001B[0m\u001B[48;2;155;110;66m\u001B[38;2;155;110;66m\u2584\u001B[0m\u001B[48;2;155;110;66m\u001B[38;2;155;110;66m\u2584\u001B[0m\u001B[48;2;155;110;66m\u001B[38;2;155;110;66m\u2584\u001B[0m\u001B[48;2;155;110;66m\u001B[38;2;155;110;66m\u2584\u001B[0m\u001B[48;2;155;110;66m\u001B[38;2;155;110;66m\u2584\u001B[0m\u001B[48;2;155;110;66m\u001B[38;2;155;110;66m\u2584\u001B[0m\u001B[48;2;155;110;66m\u001B[38;2;155;110;66m\u2584\u001B[0m\u001B[48;2;155;110;66m\u001B[38;2;155;110;66m\u2584\u001B[0m\u001B[48;2;155;110;66m\u001B[38;2;155;110;66m\u2584\u001B[0m\u001B[48;2;155;110;66m\u001B[38;2;155;110;66m\u2584\u001B[0m\u001B[48;2;155;110;66m\u001B[38;2;155;110;66m\u2584\u001B[0m\u001B[48;2;155;110;66m\u001B[38;2;155;110;66m\u2584\u001B[0m\u001B[48;2;155;110;66m\u001B[38;2;155;110;66m\u2584\u001B[0m\u001B[48;2;155;110;66m\u001B[38;2;155;110;66m\u2584\u001B[0m\u001B[48;2;155;110;66m\u001B[38;2;155;110;66m\u2584\u001B[0m\u001B[48;2;155;110;66m\u001B[38;2;155;110;66m\u2584\u001B[0m\u001B[48;2;0;0;0m\u001B[38;2;146;104;62m\u2584\u001B[0m  \n \u001B[48;2;0;0;0m\u001B[38;2;0;0;0m\u2584\u001B[0m\u001B[48;2;255;202;40m\u001B[38;2;255;202;40m\u2584\u001B[0m\u001B[48;2;0;0;0m\u001B[38;2;0;0;0m\u2584\u001B[0m\u001B[48;2;255;202;40m\u001B[38;2;255;202;40m\u2584\u001B[0m\u001B[48;2;0;0;0m\u001B[38;2;0;0;0m\u2584\u001B[0m\u001B[48;2;7;6;1m\u001B[38;2;255;202;40m\u2584\u001B[0m\u001B[48;2;255;202;40m\u001B[38;2;0;0;0m\u2584\u001B[0m\u001B[48;2;255;202;40m\u001B[38;2;255;202;40m\u2584\u001B[0m\u001B[48;2;255;202;40m\u001B[38;2;255;202;40m\u2584\u001B[0m\u001B[48;2;0;0;0m\u001B[38;2;0;0;0m\u2584\u001B[0m\u001B[48;2;155;110;66m\u001B[38;2;0;0;0m\u2584\u001B[0m\u001B[48;2;155;110;66m\u001B[38;2;0;0;0m\u2584\u001B[0m\u001B[48;2;155;110;66m\u001B[38;2;0;0;0m\u2584\u001B[0m\u001B[48;2;155;110;66m\u001B[38;2;0;0;0m\u2584\u001B[0m\u001B[48;2;155;110;66m\u001B[38;2;0;0;0m\u2584\u001B[0m\u001B[48;2;155;110;66m\u001B[38;2;0;0;0m\u2584\u001B[0m\u001B[48;2;155;110;66m\u001B[38;2;0;0;0m\u2584\u001B[0m\u001B[48;2;155;110;66m\u001B[38;2;0;0;0m\u2584\u001B[0m\u001B[48;2;155;110;66m\u001B[38;2;0;0;0m\u2584\u001B[0m\u001B[48;2;155;110;66m\u001B[38;2;0;0;0m\u2584\u001B[0m\u001B[48;2;155;110;66m\u001B[38;2;0;0;0m\u2584\u001B[0m\u001B[48;2;155;110;66m\u001B[38;2;0;0;0m\u2584\u001B[0m\u001B[48;2;155;110;66m\u001B[38;2;74;52;31m\u2584\u001B[0m\u001B[48;2;155;110;66m\u001B[38;2;155;110;66m\u2584\u001B[0m\u001B[48;2;155;110;66m\u001B[38;2;155;110;66m\u2584\u001B[0m\u001B[48;2;155;110;66m\u001B[38;2;155;110;66m\u2584\u001B[0m\u001B[48;2;155;110;66m\u001B[38;2;0;0;0m\u2584\u001B[0m\u001B[48;2;155;110;66m\u001B[38;2;0;0;0m\u2584\u001B[0m\u001B[48;2;155;110;66m\u001B[38;2;0;0;0m\u2584\u001B[0m\u001B[48;2;0;0;0m\u001B[38;2;0;0;0m\u2584\u001B[0m \n \u001B[48;2;0;0;0m\u001B[38;2;0;0;0m\u2584\u001B[0m\u001B[48;2;255;202;40m\u001B[38;2;255;202;40m\u2584\u001B[0m\u001B[48;2;0;0;0m\u001B[38;2;0;0;0m\u2584\u001B[0m\u001B[48;2;255;202;40m\u001B[38;2;255;202;40m\u2584\u001B[0m\u001B[48;2;255;202;40m\u001B[38;2;0;0;0m\u2584\u001B[0m\u001B[48;2;255;202;40m\u001B[38;2;255;202;40m\u2584\u001B[0m\u001B[48;2;0;0;0m\u001B[38;2;255;202;40m\u2584\u001B[0m\u001B[48;2;0;0;0m\u001B[38;2;0;0;0m\u2584\u001B[0m\u001B[48;2;255;202;40m\u001B[38;2;255;202;40m\u2584\u001B[0m\u001B[48;2;0;0;0m\u001B[38;2;0;0;0m\u2584\u001B[0m\u001B[48;2;155;110;66m\u001B[38;2;155;110;66m\u2584\u001B[0m\u001B[48;2;155;110;66m\u001B[38;2;155;110;66m\u2584\u001B[0m\u001B[48;2;155;110;66m\u001B[38;2;155;110;66m\u2584\u001B[0m\u001B[48;2;155;110;66m\u001B[38;2;155;110;66m\u2584\u001B[0m\u001B[48;2;155;110;66m\u001B[38;2;155;110;66m\u2584\u001B[0m\u001B[48;2;155;110;66m\u001B[38;2;155;110;66m\u2584\u001B[0m\u001B[48;2;155;110;66m\u001B[38;2;155;110;66m\u2584\u001B[0m\u001B[48;2;155;110;66m\u001B[38;2;155;110;66m\u2584\u001B[0m\u001B[48;2;155;110;66m\u001B[38;2;155;110;66m\u2584\u001B[0m\u001B[48;2;155;110;66m\u001B[38;2;155;110;66m\u2584\u001B[0m\u001B[48;2;155;110;66m\u001B[38;2;155;110;66m\u2584\u001B[0m\u001B[48;2;155;110;66m\u001B[38;2;155;110;66m\u2584\u001B[0m\u001B[48;2;155;110;66m\u001B[38;2;155;110;66m\u2584\u001B[0m\u001B[48;2;155;110;66m\u001B[38;2;155;110;66m\u2584\u001B[0m\u001B[48;2;155;110;66m\u001B[38;2;155;110;66m\u2584\u001B[0m\u001B[48;2;155;110;66m\u001B[38;2;155;110;66m\u2584\u001B[0m\u001B[48;2;155;110;66m\u001B[38;2;155;110;66m\u2584\u001B[0m\u001B[48;2;155;110;66m\u001B[38;2;155;110;66m\u2584\u001B[0m\u001B[48;2;155;110;66m\u001B[38;2;155;110;66m\u2584\u001B[0m\u001B[48;2;0;0;0m\u001B[38;2;0;0;0m\u2584\u001B[0m\n \u001B[38;2;0;0;0m\u2580\u001B[0m\u001B[48;2;255;202;40m\u001B[38;2;244;194;39m\u2584\u001B[0m\u001B[48;2;0;0;0m\u001B[38;2;255;202;40m\u2584\u001B[0m\u001B[48;2;255;202;40m\u001B[38;2;106;84;17m\u2584\u001B[0m\u001B[48;2;0;0;0m\u001B[38;2;255;202;40m\u2584\u001B[0m\u001B[48;2;255;202;40m\u001B[38;2;47;38;8m\u2584\u001B[0m\u001B[48;2;255;202;40m\u001B[38;2;255;202;40m\u2584\u001B[0m\u001B[48;2;0;0;0m\u001B[38;2;0;0;0m\u2584\u001B[0m\u001B[48;2;255;202;40m\u001B[38;2;255;202;40m\u2584\u001B[0m\u001B[48;2;0;0;0m\u001B[38;2;0;0;0m\u2584\u001B[0m\u001B[48;2;0;0;0m\u001B[38;2;155;110;66m\u2584\u001B[0m\u001B[48;2;0;0;0m\u001B[38;2;155;110;66m\u2584\u001B[0m\u001B[48;2;0;0;0m\u001B[38;2;155;110;66m\u2584\u001B[0m\u001B[48;2;155;110;66m\u001B[38;2;155;110;66m\u2584\u001B[0m\u001B[48;2;155;110;66m\u001B[38;2;155;110;66m\u2584\u001B[0m\u001B[48;2;155;110;66m\u001B[38;2;155;110;66m\u2584\u001B[0m\u001B[48;2;130;92;55m\u001B[38;2;155;110;66m\u2584\u001B[0m\u001B[48;2;0;0;0m\u001B[38;2;155;110;66m\u2584\u001B[0m\u001B[48;2;0;0;0m\u001B[38;2;155;110;66m\u2584\u001B[0m\u001B[48;2;0;0;0m\u001B[38;2;155;110;66m\u2584\u001B[0m\u001B[48;2;0;0;0m\u001B[38;2;155;110;66m\u2584\u001B[0m\u001B[48;2;0;0;0m\u001B[38;2;155;110;66m\u2584\u001B[0m\u001B[48;2;0;0;0m\u001B[38;2;155;110;66m\u2584\u001B[0m\u001B[48;2;0;0;0m\u001B[38;2;155;110;66m\u2584\u001B[0m\u001B[48;2;0;0;0m\u001B[38;2;155;110;66m\u2584\u001B[0m\u001B[48;2;0;0;0m\u001B[38;2;155;110;66m\u2584\u001B[0m\u001B[48;2;0;0;0m\u001B[38;2;155;110;66m\u2584\u001B[0m\u001B[48;2;155;110;66m\u001B[38;2;155;110;66m\u2584\u001B[0m\u001B[48;2;155;110;66m\u001B[38;2;141;100;60m\u2584\u001B[0m\u001B[38;2;0;0;0m\u2580\u001B[0m \n  \u001B[48;2;0;0;0m\u001B[38;2;0;0;0m\u2584\u001B[0m\u001B[48;2;255;202;40m\u001B[38;2;255;202;40m\u2584\u001B[0m\u001B[48;2;0;0;0m\u001B[38;2;255;202;40m\u2584\u001B[0m\u001B[48;2;255;202;40m\u001B[38;2;0;0;0m\u2584\u001B[0m\u001B[48;2;255;202;40m\u001B[38;2;0;0;0m\u2584\u001B[0m\u001B[48;2;0;0;0m\u001B[38;2;255;202;40m\u2584\u001B[0m\u001B[48;2;255;202;40m\u001B[38;2;255;202;40m\u2584\u001B[0m\u001B[48;2;202;160;32m\u001B[38;2;0;0;0m\u2584\u001B[0m\u001B[48;2;107;76;45m\u001B[38;2;0;0;0m\u2584\u001B[0m\u001B[48;2;155;110;66m\u001B[38;2;0;0;0m\u2584\u001B[0m\u001B[48;2;155;110;66m\u001B[38;2;0;0;0m\u2584\u001B[0m\u001B[48;2;155;110;66m\u001B[38;2;0;0;0m\u2584\u001B[0m\u001B[48;2;155;110;66m\u001B[38;2;0;0;0m\u2584\u001B[0m\u001B[48;2;155;110;66m\u001B[38;2;0;0;0m\u2584\u001B[0m\u001B[48;2;155;110;66m\u001B[38;2;155;110;66m\u2584\u001B[0m\u001B[48;2;155;110;66m\u001B[38;2;155;110;66m\u2584\u001B[0m\u001B[48;2;155;110;66m\u001B[38;2;155;110;66m\u2584\u001B[0m\u001B[48;2;155;110;66m\u001B[38;2;155;110;66m\u2584\u001B[0m\u001B[48;2;155;110;66m\u001B[38;2;155;110;66m\u2584\u001B[0m\u001B[48;2;155;110;66m\u001B[38;2;0;0;0m\u2584\u001B[0m\u001B[48;2;155;110;66m\u001B[38;2;0;0;0m\u2584\u001B[0m\u001B[48;2;155;110;66m\u001B[38;2;0;0;0m\u2584\u001B[0m\u001B[48;2;155;110;66m\u001B[38;2;155;110;66m\u2584\u001B[0m\u001B[48;2;155;110;66m\u001B[38;2;0;0;0m\u2584\u001B[0m\u001B[48;2;155;110;66m\u001B[38;2;0;0;0m\u2584\u001B[0m\u001B[48;2;155;110;66m\u001B[38;2;0;0;0m\u2584\u001B[0m\u001B[48;2;155;110;66m\u001B[38;2;0;0;0m\u2584\u001B[0m\u001B[48;2;0;0;0m\u001B[38;2;0;0;0m\u2584\u001B[0m  \n   \u001B[38;2;0;0;0m\u2580\u001B[0m\u001B[48;2;255;202;40m\u001B[38;2;0;0;0m\u2584\u001B[0m\u001B[48;2;255;202;40m\u001B[38;2;0;0;0m\u2584\u001B[0m\u001B[48;2;255;202;40m\u001B[38;2;0;0;0m\u2584\u001B[0m\u001B[48;2;255;202;40m\u001B[38;2;0;0;0m\u2584\u001B[0m\u001B[48;2;0;0;0m\u001B[38;2;0;0;0m\u2584\u001B[0m\u001B[48;2;155;110;66m\u001B[38;2;132;94;57m\u2584\u001B[0m\u001B[48;2;155;110;66m\u001B[38;2;155;110;66m\u2584\u001B[0m\u001B[48;2;155;110;66m\u001B[38;2;0;0;0m\u2584\u001B[0m\u001B[48;2;155;110;66m\u001B[38;2;0;0;0m\u2584\u001B[0m\u001B[48;2;155;110;66m\u001B[38;2;155;110;66m\u2584\u001B[0m\u001B[48;2;155;110;66m\u001B[38;2;0;0;0m\u2584\u001B[0m\u001B[48;2;155;110;66m\u001B[38;2;0;0;0m\u2584\u001B[0m\u001B[48;2;155;110;66m\u001B[38;2;0;0;0m\u2584\u001B[0m\u001B[48;2;155;110;66m\u001B[38;2;0;0;0m\u2584\u001B[0m\u001B[48;2;155;110;66m\u001B[38;2;0;0;0m\u2584\u001B[0m\u001B[48;2;155;110;66m\u001B[38;2;0;0;0m\u2584\u001B[0m\u001B[48;2;155;110;66m\u001B[38;2;0;0;0m\u2584\u001B[0m\u001B[48;2;155;110;66m\u001B[38;2;0;0;0m\u2584\u001B[0m\u001B[48;2;155;110;66m\u001B[38;2;0;0;0m\u2584\u001B[0m\u001B[48;2;155;110;66m\u001B[38;2;0;0;0m\u2584\u001B[0m\u001B[48;2;155;110;66m\u001B[38;2;0;0;0m\u2584\u001B[0m\u001B[48;2;155;110;66m\u001B[38;2;0;0;0m\u2584\u001B[0m\u001B[48;2;155;110;66m\u001B[38;2;0;0;0m\u2584\u001B[0m\u001B[48;2;155;110;66m\u001B[38;2;0;0;0m\u2584\u001B[0m\u001B[38;2;0;0;0m\u2580\u001B[0m   \n            \n";

    public static synchronized String generateBigAlphaKey(int key_length)
    {
        try
        {
            // make sure we never get the same millis!
            Thread.sleep(1);
        } catch (Exception e) {}
        Random n = new Random(System.currentTimeMillis());
        String alpha = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
        StringBuffer return_key = new StringBuffer();
        for (int i = 0; i < key_length; i++)
        {
            return_key.append(alpha.charAt(n.nextInt(alpha.length())));
        }
        String randKey = return_key.toString();
        //System.err.println("Generated Rule ID: " + randKey);
        return randKey;
    }

    public static boolean isMatch(String line, String filter)
    {
        if (filter != null)
        {
            try
            {
                String exp = filter.replaceAll("\\(", "( ").replaceAll("\\)", " )");
                JexlContext jexlContext = new MapContext();
                StringTokenizer st = new StringTokenizer(exp);
                String newFilter = filter + "";
                while(st.hasMoreElements())
                {
                    String el = st.nextToken().trim();
                    if (el.equals("(") || el.equals(")") || el.equals("||") || el.equals("&&"))
                    {
                        //Ignore
                    } else {
                        String var = generateBigAlphaKey(3);
                        if (el.startsWith("!"))
                        {
                            boolean contained = !line.contains(el.substring(1));
                            jexlContext.set(var, contained);
                            newFilter = newFilter.replaceAll(Pattern.quote(el),var);
                        } else {
                            boolean contained = line.contains(el);
                            jexlContext.set(var, contained);
                            newFilter = newFilter.replaceAll(Pattern.quote(el),var);
                        }
                    }
                }
                JexlExpression expression = jexl.createExpression(newFilter);
                return (Boolean)expression.evaluate(jexlContext);
            } catch (Exception e) {
                return false;
            }
        } else {
            return true;
        }
    }

    public static void main(String[] args)
    {
        JexlFeatures features = new JexlFeatures()
                .loops(false)
                .sideEffectGlobal(false)
                .sideEffect(false);
        // Restricted permissions to a safe set but with URI allowed
        LogSpoutMain.jexl = new JexlBuilder().features(features).create();

        org.eclipse.jetty.util.log.Log.setLog(new NoLogging());
        //System.setProperty("org.eclipse.jetty.util.log.class", "org.eclipse.jetty.util.log.StdErrLog");
        System.setProperty("org.eclipse.jetty.LEVEL", "OFF");
        //System.setProperty("org.eclipse.jetty.server.Request.maxFormContentSize","1000000000");
        
        Options options = commandLineOptions();
        CommandLineParser parser = new DefaultParser();
        LogSpoutMain.settings = new JSONObject();
        LogSpoutMain.configFile = null;

        boolean stdoutLogs = false;
        boolean browserPrompt = false;
        try
        {
            final CommandLine cmd = parser.parse(options, args);

            if (cmd.hasOption("?"))
            {
                showHelp(options);
            }
            if (cmd.hasOption("s"))
            {
                stdoutLogs = true;
            }
            if (cmd.hasOption("v"))
            {
                LogSpoutMain.verbose = true;
            }
            boolean temporaryConfig = "_TEMP_FILE_".equals(cmd.getOptionValue("f"));
            boolean generatedConfig = cmd.hasOption("d") || cmd.hasOption("e");
            boolean automaticTemporaryConfig = generatedConfig && !cmd.hasOption("f")
                && !cmd.hasOption("g") && (cmd.hasOption("s") || cmd.hasOption("a"));
            boolean defaultDirectoryConfig = generatedConfig && cmd.hasOption("g") && !cmd.hasOption("f");
            boolean overwriteDefaultConfig = false;
            if (temporaryConfig && !generatedConfig)
                throw new IllegalArgumentException("-f _TEMP_FILE_ requires -d / --directory or -e / --exec");
            if (generatedConfig)
            {
                LogSpoutMain.settings = cmd.hasOption("d")
                    ? generateDirectoryConfig(cmd.getOptionValue("d", "."))
                    : new JSONObject().put("_name", "Processes").put("_sources", new JSONArray());
                if (cmd.hasOption("e"))
                {
                    for (String command : cmd.getOptionValues("e"))
                        LogSpoutMain.settings.getJSONArray("_sources").put(new JSONObject()
                            .put("_name", command).put("_type", "process")
                            .put("_execute", splitCommand(command)));
                }
                if ((temporaryConfig || automaticTemporaryConfig) && !cmd.hasOption("g"))
                {
                    LogSpoutMain.configFile = Files.createTempFile("log-spout-", ".json").toFile();
                    LogSpoutMain.configFile.deleteOnExit();
                } else if (cmd.hasOption("f") && !temporaryConfig) {
                    LogSpoutMain.configFile = new File(cmd.getOptionValue("f"));
                } else if (defaultDirectoryConfig) {
                    LogSpoutMain.configFile = new File("./.log-spout.json");
                    if (LogSpoutMain.configFile.exists())
                    {
                        System.err.print("Warning: " + LogSpoutMain.configFile.getAbsolutePath()
                            + " already exists. Overwrite? [y/N] ");
                        System.err.flush();
                        String answer = new BufferedReader(new InputStreamReader(System.in)).readLine();
                        if (answer == null || !(answer.trim().equalsIgnoreCase("y")
                            || answer.trim().equalsIgnoreCase("yes")))
                        {
                            System.err.println("Cancelled; existing config was not overwritten.");
                            return;
                        }
                        overwriteDefaultConfig = true;
                    }
                }
            } else if (cmd.hasOption("f"))
            {
                String filename = cmd.getOptionValue("f");
                File file = new File(filename);
                if (file.exists())
                {
                    LogSpoutMain.settings = loadJSONObject(file);
                    LogSpoutMain.configFile = file;
                } else {
                    System.err.println("Cannot load file: " + filename);
                    System.exit(0);
                }
            } else if (!cmd.hasOption("c")) {
                File file = new File("./.log-spout.json");
                if (file.exists())
                {
                    LogSpoutMain.settings = loadJSONObject(file);
                    LogSpoutMain.configFile = file;
                } else {
                    showHelp(options);
                }
            }

            if (cmd.hasOption("n"))
            {
                LogSpoutMain.settings.put("_name", cmd.getOptionValue("n"));
            }

            if (cmd.hasOption("x"))
            {
                LogSpoutMain.settings.put("_filter", cmd.getOptionValue("x"));
            }

            if (cmd.hasOption("c"))
            {
                String[] connect = cmd.getOptionValues("c");
                if (connect == null || connect[0].isBlank())
                    throw new IllegalArgumentException("-c / --connect requires a server address");
                LogSpoutMain.settings.put("_remote", connect[0]);
                if (connect.length > 1)
                    LogSpoutMain.settings.put("_remote_password", connect[1]);
            }

            if (cmd.hasOption("l"))
            {
                LogSpoutMain.settings.put("_select", cmd.getOptionValue("l"));
            }

            if (cmd.hasOption("p"))
            {
                if (!cmd.hasOption("a"))
                    System.err.println("Warning: -p / --password only applies to the local api (-a); "
                        + "use -c <server> <password> for remote connections");
                else if (!settings.has("apiPassword"))
                    LogSpoutMain.settings.put("apiPassword", cmd.getOptionValue("p"));
            }

            if (cmd.hasOption("a"))
            {
                LogSpoutMain.settings.put("apiPort", Integer.valueOf(cmd.getOptionValue("a", "8662")));
                browserPrompt = true;
            }

            if (generatedConfig)
            {
                if (LogSpoutMain.configFile == null)
                {
                    if (!cmd.hasOption("g"))
                    {
                        System.out.println(LogSpoutMain.settings.toString(2));
                        return;
                    }
                } else {
                    if (defaultDirectoryConfig && !overwriteDefaultConfig)
                        Files.writeString(LogSpoutMain.configFile.toPath(), LogSpoutMain.settings.toString(2),
                            StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                    else
                        Files.writeString(LogSpoutMain.configFile.toPath(), LogSpoutMain.settings.toString(2));
                }
            }

            if (cmd.hasOption("g"))
            {
                File filename = launchScriptFile(cmd.getOptionValue("g"));
                Files.writeString(filename.toPath(), generateLaunchScript(cmd));
                if (!isWindows() && !filename.setExecutable(true))
                    throw new IOException("Cannot make script executable: " + filename);
                System.err.println("\u001b[1;32mGenerated: \u001b[0m" + filename);
                return;
            }

            if (!LogSpoutMain.settings.has("hostname"))
            {
                LogSpoutMain.settings.put("hostname", getLocalHostname());
            }
        } catch (Exception e) {
            e.printStackTrace(System.err);
            System.exit(1);
            return;
        }
        final LogConnectionContainer lcc = LogConnectionParser.parseInitial(LogSpoutMain.settings);
        if (stdoutLogs || (settings.has("apiPort") || settings.has("apiPassword")))
        {
            System.err.println(LogSpoutMain.logSpoutAsciiArt);
        }
        if (stdoutLogs)
        {
            lcc.addLogConnectionListener(new LogConnectionListener() {

                @Override
                public void onLine(String line, ArrayList<String> logPath, LogConnection connection) {
                    if (LogSpoutMain.settings.has("_select"))
                    {
                        String select = LogSpoutMain.settings.getString("_select");
                        if (logPath.contains(select))
                        {
                            System.out.println(line);
                        }
                    } else {
                        System.out.println(line);
                    }
                }

                @Override
                public void onLogDisconnectError(LogConnection connection, String err, Exception exception) {
                    
                }
                
            });
        }
        if (lcc != null && (settings.has("apiPort") || settings.has("apiPassword")))
        {
            LogSpoutMain.apiWebServer = new APIWebServer(lcc);
            Runtime.getRuntime().addShutdownHook(new Thread() {
                public void run()
                {
                    lcc.disconnect();
                }
            });
            showIPS();
            if (browserPrompt && LogSpoutMain.apiWebServer.getLocalPort() > 0)
                offerBrowser(LogSpoutMain.apiWebServer.getLocalPort());
        }
        while(lcc != null)
        {
            try
            {
                Thread.sleep(1000);
            } catch (Exception e) {}
        }
    }

    static Options commandLineOptions()
    {
        Options options = new Options();
        options.addOption(new Option("?", "help", false, "Shows help"));
        options.addOption(new Option("f", "config", true, "Specify config file (use _TEMP_FILE_ with -d or -e for a fresh temporary config)"));
        Option directoryOption = new Option("d", "directory", true,
            "Generate a tail config by recursively scanning a directory (default: current directory)");
        directoryOption.setOptionalArg(true);
        options.addOption(directoryOption);
        options.addOption(new Option("n", "name", true, "Override the root config name"));
        options.addOption(new Option("e", "exec", true, "Add a process command to a generated config (repeatable)"));
        options.addOption(new Option("x", "expression", true, "Specify an expression for filtering log data"));
        options.addOption(new Option("s", "stdout", false, "Print logs to STDOUT"));
        Option connectOption = Option.builder("c").longOpt("connect").numberOfArgs(2).optionalArg(true)
            .argName("server> <password").desc("Connect to a logspout server, with an optional password"
                + " (hostname[:port] tries wss:// then ws://, each with and without /logspout/)").get();
        options.addOption(connectOption);
        options.addOption(new Option("g", "generate", true, "Generate a launch script (Windows: .cmd, POSIX: Bash)"));
        options.addOption(new Option("p", "password", true, "Set password for the local api server -a"));
        options.addOption(new Option("l", "log", true, "Select which log to view"));
        options.addOption(new Option("v", "verbose", false, "Turn on verbose output"));
        Option apiOption = new Option("a", "api", true, "Turn on api server, optional argument to specify port (default 8662)");
        apiOption.setOptionalArg(true);
        options.addOption(apiOption);
        return options;
    }

    static JSONArray splitCommand(String command)
    {
        JSONArray arguments = new JSONArray();
        StringBuilder argument = new StringBuilder();
        char quote = 0;
        boolean started = false;
        for (int i = 0; i < command.length(); i++)
        {
            char character = command.charAt(i);
            if (character == '\\' && quote != '\'' && i + 1 < command.length()
                && (command.charAt(i + 1) == '\\' || command.charAt(i + 1) == '"'
                    || (quote == 0 && (Character.isWhitespace(command.charAt(i + 1))
                        || command.charAt(i + 1) == '\''))))
            {
                argument.append(command.charAt(++i));
                started = true;
            } else if (quote != 0) {
                if (character == quote)
                    quote = 0;
                else
                    argument.append(character);
            } else if (character == '\'' || character == '"') {
                quote = character;
                started = true;
            } else if (Character.isWhitespace(character)) {
                if (started)
                {
                    arguments.put(argument.toString());
                    argument.setLength(0);
                    started = false;
                }
            } else {
                argument.append(character);
                started = true;
            }
        }
        if (quote != 0)
            throw new IllegalArgumentException("Unclosed quote in command: " + command);
        if (started)
            arguments.put(argument.toString());
        if (arguments.isEmpty() || arguments.getString(0).isEmpty())
            throw new IllegalArgumentException("A process command requires a non-empty executable");
        return arguments;
    }

    static JSONObject generateDirectoryConfig(String directory) throws IOException
    {
        Path root = Path.of(directory).toAbsolutePath().normalize();
        if (!Files.isDirectory(root))
            throw new IOException("Not a directory: " + directory);
        ArrayList<Path> files = new ArrayList<Path>();
        Files.walkFileTree(root, new SimpleFileVisitor<Path>()
        {
            @Override
            public FileVisitResult visitFile(Path path, BasicFileAttributes attributes) throws IOException
            {
                String name = path.getFileName().toString();
                if (Files.isRegularFile(path) && (name.endsWith(".log") || name.endsWith(".out")
                    || name.endsWith(".err") || name.equals("syslog") || name.equals("dmesg")
                    || name.equals("messages")))
                {
                    try
                    {
                        if (isLikelyBinary(path))
                        {
                            System.err.println("\u001b[1;33mSkipping binary log: \u001b[0m" + path);
                            return FileVisitResult.CONTINUE;
                        }
                    } catch (FileSystemException error) {
                        return visitFileFailed(path, error);
                    }
                    files.add(path);
                    System.err.println("\u001b[1;32mFound Log: \u001b[0m" + path);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path path, IOException error) throws IOException
            {
                if (error instanceof FileSystemException)
                {
                    System.err.println("\u001b[1;33mSkipping inaccessible path: \u001b[0m" + path);
                    return FileVisitResult.CONTINUE;
                }
                throw error;
            }
        });
        Collections.sort(files);
        JSONObject config = new JSONObject().put("_name", directory.equals(".") ? root.toString() : directory)
            .put("_sources", new JSONArray());
        Map<Path, JSONObject> containers = new HashMap<Path, JSONObject>();
        containers.put(root, config);
        for (Path path : files)
        {
            Path parent = root;
            JSONObject container = config;
            if (!path.getParent().equals(root))
            {
                for (Path component : root.relativize(path.getParent()))
                {
                    parent = parent.resolve(component);
                    JSONObject child = containers.get(parent);
                    if (child == null)
                    {
                        child = new JSONObject().put("_name", root.relativize(parent).toString()
                            .replace(File.separatorChar, '/') + "/")
                            .put("_sources", new JSONArray());
                        container.getJSONArray("_sources").put(child);
                        containers.put(parent, child);
                    }
                    container = child;
                }
            }
            container.getJSONArray("_sources").put(new JSONObject()
                .put("_type", "tail").put("_file", path.toString())
                .put("_name", root.relativize(path).toString().replace(File.separatorChar, '/')));
        }
        collapseDirectoryChains(config);
        return config;
    }

    private static void collapseDirectoryChains(JSONObject container)
    {
        JSONArray sources = container.getJSONArray("_sources");
        for (int i = 0; i < sources.length(); i++)
        {
            JSONObject source = sources.getJSONObject(i);
            if (!source.has("_sources"))
                continue;
            collapseDirectoryChains(source);
            JSONArray children = source.getJSONArray("_sources");
            while (children.length() == 1 && children.getJSONObject(0).has("_sources"))
            {
                source = children.getJSONObject(0);
                children = source.getJSONArray("_sources");
            }
            sources.put(i, source);
        }
    }

    static boolean isLikelyBinary(Path path) throws IOException
    {
        byte[] sample;
        try (InputStream input = Files.newInputStream(path))
        {
            sample = input.readNBytes(8192);
        }
        int nonText = 0;
        for (byte value : sample)
        {
            int character = value & 0xff;
            if (character == 0)
                return true;
            if ((character < 32 && character != '\t' && character != '\n'
                && character != '\r' && character != 27) || character == 127)
                nonText++;
        }
        return nonText * 10 > sample.length;
    }

    static String generateLaunchScript(CommandLine cmd)
    {
        boolean windows = isWindows();
        StringBuilder command = new StringBuilder(windows
            ? "@echo off\r\nsetlocal DisableDelayedExpansion\r\nlog-spout.exe"
            : "#!/bin/bash\nlog-spout");
        boolean generatedConfig = cmd.hasOption("d") || cmd.hasOption("e");
        boolean temporaryConfig = generatedConfig && "_TEMP_FILE_".equals(cmd.getOptionValue("f"));
        for (Option option : cmd.getOptions())
        {
            if (option.getOpt().equals("g")
                || ((option.getOpt().equals("d") || option.getOpt().equals("e")) && !temporaryConfig))
                continue;
            command.append(" -").append(option.getOpt());
            if (option.getValues() != null)
            {
                for (String value : option.getValues())
                    command.append(" ").append(windows ? batchQuote(value) : shellQuote(value));
            } else if (option.getOpt().equals("d")) {
                command.append(" ").append(windows ? batchQuote(".") : shellQuote("."));
            }
        }
        if (generatedConfig && !cmd.hasOption("f"))
        {
            String file = Path.of(".log-spout.json").toAbsolutePath().toString();
            command.append(" -f ").append(windows ? batchQuote(file) : shellQuote(file));
        }
        if (!cmd.hasOption("s") && !cmd.hasOption("a"))
            command.append(" -s");
        for (String argument : cmd.getArgList())
            command.append(" ").append(windows ? batchQuote(argument) : shellQuote(argument));
        return command.append(windows ? "\r\n" : "\n").toString();
    }

    static File launchScriptFile(String filename)
    {
        File file = new File(filename);
        if (!isWindows())
            return file;
        String name = file.getName();
        int extension = name.lastIndexOf('.');
        if (extension > 0)
            name = name.substring(0, extension);
        return new File(file.getParentFile(), name + ".cmd");
    }

    private static boolean isWindows()
    {
        return File.separatorChar == '\\';
    }

    private static String batchQuote(String value)
    {
        if (value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0 || value.indexOf('\0') >= 0)
            throw new IllegalArgumentException("Windows script arguments cannot contain line breaks or NUL");
        StringBuilder quoted = new StringBuilder("\"");
        int backslashes = 0;
        for (char character : value.toCharArray())
        {
            if (character == '\\')
            {
                backslashes++;
            } else {
                quoted.append("\\".repeat(character == '"' ? backslashes * 2 + 1 : backslashes));
                quoted.append(character);
                backslashes = 0;
            }
        }
        quoted.append("\\".repeat(backslashes * 2)).append('"');
        StringBuilder escaped = new StringBuilder();
        for (char character : quoted.toString().toCharArray())
        {
            if (character == '%')
                escaped.append("%%");
            else
            {
                // CMD escaping is separate from the Windows process argument quoting above.
                if ("\"^&|<>()".indexOf(character) >= 0)
                    escaped.append('^');
                escaped.append(character);
            }
        }
        return escaped.toString();
    }

    private static String shellQuote(String value)
    {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    public static void showIPS()
    {
        try
        {
            System.err.println("\u001b[1;34mWeb Interface:\u001b[0m");
            Enumeration<NetworkInterface> nets = NetworkInterface.getNetworkInterfaces();
            for (NetworkInterface netint : Collections.list(nets))
            {
                //System.err.println("Interface: " + netint.getDisplayName());
                // Create a JmDNS instance
                Enumeration<InetAddress> addresses = netint.getInetAddresses();
                Collections.list(addresses).forEach((address) -> {
                    try
                    {
                        URI webAddress = new URI("http", null, address.getHostAddress().replace("%", "%25"),
                            LogSpoutMain.settings.optInt("apiPort", 8662), "/", null, null);
                        System.err.println("  " + webAddress.toASCIIString());
                    } catch (URISyntaxException e) {
                        System.err.println("  Unable to format web address for " + address.getHostAddress() + ": " + e.getMessage());
                    }
                });
            }
        } catch (Exception e) {

        }
    }
    
    public static void showHelp(Options options)
    {
        HelpFormatter formatter = new HelpFormatter();
        formatter.printHelp( "log-spout", "Log Spout: A tool for tapping into several log streams at once", options, "" );
        System.exit(0);
    }

    public static JSONObject loadJSONObject(File file)
    {
        try
        {
            FileInputStream fis = new FileInputStream(file);
            StringBuilder builder = new StringBuilder();
            int ch;
            while((ch = fis.read()) != -1){
                builder.append((char)ch);
            }
            fis.close();
            JSONObject props = new JSONObject(builder.toString());
            return props;
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    public static void saveJSONObject(File file, JSONObject obj)
    {
        try
        {
            FileOutputStream fos = new FileOutputStream(file);
            PrintStream ps = new PrintStream(fos);
            ps.print(obj.toString(2));
            ps.close();
            fos.close();
        } catch (Exception e) {
        }
    }

    public static synchronized void setRemotePresets(JSONObject owner, JSONArray presets)
    {
        if (owner == LogSpoutMain.settings)
        {
            remotePresetOwner = owner;
            remotePresets = new JSONArray(presets.toString());
        }
    }

    public static synchronized JSONArray getPresets()
    {
        JSONArray result = new JSONArray();
        JSONArray local = settings == null ? null : settings.optJSONArray("_presets");
        if (local != null)
            for (int i = 0; i < local.length(); i++)
                result.put(new JSONObject(local.getJSONObject(i).toString()));
        if (remotePresetOwner == settings)
            for (int i = 0; i < remotePresets.length(); i++)
            {
                JSONObject preset = remotePresets.getJSONObject(i);
                if (findPreset(result, preset.getString("name")) == null)
                    result.put(new JSONObject(preset.toString()));
            }
        return result;
    }

    private static JSONObject findPreset(JSONArray presets, String name)
    {
        if (presets != null)
            for (int i = 0; i < presets.length(); i++)
            {
                JSONObject preset = presets.optJSONObject(i);
                if (preset != null && name.equals(preset.optString("name")))
                    return preset;
            }
        return null;
    }

    public static synchronized boolean savePreset(String name, String log, String filter)
    {
        if (name == null || "".equals(name.trim()))
            return false;
        name = name.trim();
        if (LogSpoutMain.settings == null)
            return false;
        JSONArray presets = LogSpoutMain.settings.optJSONArray("_presets");
        JSONObject shared = remotePresetOwner == settings ? findPreset(remotePresets, name) : null;
        if (shared != null && findPreset(presets, name) == null)
        {
            shared.put("log", log == null ? "" : log);
            shared.put("filter", filter == null ? "" : filter);
            return true;
        }
        if (presets == null)
        {
            presets = new JSONArray();
            LogSpoutMain.settings.put("_presets", presets);
        }
        JSONObject preset = null;
        for (int i = 0; i < presets.length(); i++)
        {
            JSONObject p = presets.optJSONObject(i);
            if (p != null && name.equals(p.optString("name", "")))
            {
                preset = p;
                break;
            }
        }
        if (preset == null)
        {
            preset = new JSONObject();
            preset.put("name", name);
            presets.put(preset);
        }
        preset.put("log", log == null ? "" : log);
        preset.put("filter", filter == null ? "" : filter);
        if (LogSpoutMain.configFile != null)
            saveJSONObject(LogSpoutMain.configFile, LogSpoutMain.settings);
        return true;
    }

    public static synchronized boolean deletePreset(String name)
    {
        if (name == null || LogSpoutMain.settings == null)
            return false;
        JSONArray presets = LogSpoutMain.settings.optJSONArray("_presets");
        if (findPreset(presets, name) == null && remotePresetOwner == settings)
        {
            for (int i = 0; i < remotePresets.length(); i++)
                if (name.equals(remotePresets.getJSONObject(i).optString("name")))
                {
                    remotePresets.remove(i);
                    return true;
                }
        }
        if (presets == null)
            return false;
        boolean removed = false;
        for (int i = presets.length() - 1; i >= 0; i--)
        {
            JSONObject p = presets.optJSONObject(i);
            if (p != null && name.equals(p.optString("name", "")))
            {
                presets.remove(i);
                removed = true;
            }
        }
        if (removed && LogSpoutMain.configFile != null)
            saveJSONObject(LogSpoutMain.configFile, LogSpoutMain.settings);
        return removed;
    }

    public static String shellExec(String cmd[])
    {
        try
        {
            Process cmdProc = Runtime.getRuntime().exec(cmd);
            cmdProc.waitFor();
            return readStreamToString(cmdProc.getInputStream());
        } catch (Exception e) {
           
        }
        return null;
    }

    static URI localBrowserAddress(int port)
    {
        return URI.create("http://127.0.0.1:" + port + "/");
    }

    static boolean awaitBrowserRequest(BufferedReader input, PrintStream output) throws IOException
    {
        output.println("\u001b[1;36m[Press enter to open in browser]\u001b[0m");
        output.flush();
        String line;
        while ((line = input.readLine()) != null)
        {
            if (line.isEmpty())
                return true;
        }
        return false;
    }

    private static void offerBrowser(int port)
    {
        try
        {
            boolean nativeImage = "runtime".equals(System.getProperty("org.graalvm.nativeimage.imagecode"));
            if (nativeImage)
            {
                if (Boolean.getBoolean("java.awt.headless") || (!isWindows()
                    && !System.getProperty("os.name").startsWith("Mac")
                    && System.getenv("DISPLAY") == null && System.getenv("WAYLAND_DISPLAY") == null))
                    return;
            } else {
                if (GraphicsEnvironment.isHeadless() || !Desktop.isDesktopSupported()
                    || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE))
                    return;
            }
            Thread browserThread = new Thread(() -> {
                try
                {
                    if (awaitBrowserRequest(new BufferedReader(new InputStreamReader(System.in)), System.err))
                    {
                        URI address = localBrowserAddress(port);
                        if (nativeImage)
                        {
                            Process launcher = new ProcessBuilder(nativeBrowserCommand(
                                System.getProperty("os.name"), address))
                                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                                .redirectError(ProcessBuilder.Redirect.INHERIT).start();
                            int status = launcher.waitFor();
                            if (status != 0)
                                throw new IOException("Browser launcher exited with status " + status);
                        } else {
                            Desktop.getDesktop().browse(address);
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    System.err.println("Browser launch interrupted.");
                } catch (IOException | AWTError | UnsupportedOperationException | SecurityException e) {
                    System.err.println("Unable to open browser: " + e.getMessage());
                }
            }, "browser-prompt");
            browserThread.setDaemon(true);
            browserThread.start();
        } catch (AWTError | UnsupportedOperationException | SecurityException e) {
            System.err.println("Unable to initialize browser support: " + e.getMessage());
        }
    }

    static String[] nativeBrowserCommand(String osName, URI address)
    {
        if (osName.startsWith("Windows"))
            return new String[] {"rundll32", "url.dll,FileProtocolHandler", address.toString()};
        if (osName.startsWith("Mac"))
            return new String[] {"open", address.toString()};
        return new String[] {"xdg-open", address.toString()};
    }

    public static String readStreamToString(InputStream is)
    {
        String result = "";
        try
        {
            java.util.Scanner s = new java.util.Scanner(is).useDelimiter("\\A");
            result = s.hasNext() ? s.next() : "";
            s.close();
        } catch (Exception e) {}
        return result;
    }

    public static String getLocalHostname()
    {
        String returnValue = "";
        Map<String, String> env = System.getenv();
        if (env.containsKey("COMPUTERNAME"))
        {
            returnValue = env.get("COMPUTERNAME");
        } else if (env.containsKey("HOSTNAME")) {
            returnValue = env.get("HOSTNAME");
        } else {
            String hostnameCommand = shellExec(new String[] {"hostname"});
            if (hostnameCommand != null)
            {
                String hostname = hostnameCommand.trim();
                if (!"".equals(hostname))
                    returnValue = hostname;
            }
        }
        if ("".equals(returnValue))
        {
            try
            {
                for(Enumeration<NetworkInterface> n = NetworkInterface.getNetworkInterfaces(); n.hasMoreElements() && "".equals(returnValue);)
                {
                    NetworkInterface ni = n.nextElement();
                    for(Enumeration<InetAddress> e = ni.getInetAddresses(); e.hasMoreElements() && "".equals(returnValue);)
                    {
                        InetAddress ia = e.nextElement();
                        if (!ia.isLoopbackAddress() && ia.isSiteLocalAddress())
                        {
                            String hostname = ia.getHostName();
                            returnValue = hostname;
                        }
                    }
                }

            } catch (Exception e) {}
        }
        if (returnValue.contains(".local"))
        {
            returnValue = returnValue.replace(".local", "");
        }
        if (returnValue.contains(".lan"))
        {
            returnValue = returnValue.replace(".lan", "");
        }
        return returnValue;
    }
}

package com.lukechenshui.jresume;

import com.beust.jcommander.JCommander;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.lukechenshui.jresume.exceptions.InvalidEnvironmentVariableException;
import com.lukechenshui.jresume.exceptions.InvalidJSONException;
import com.lukechenshui.jresume.exceptions.InvalidThemeNameException;
import com.lukechenshui.jresume.resume.Resume;
import com.lukechenshui.jresume.resume.items.Person;
import com.lukechenshui.jresume.resume.items.work.JobWork;
import com.lukechenshui.jresume.resume.items.work.VolunteerWork;
import com.lukechenshui.jresume.themes.BaseTheme;
import com.lukechenshui.jresume.themes.BasicExampleTheme;
import com.lukechenshui.jresume.themes.DefaultTheme;
import net.lingala.zip4j.core.ZipFile;
import net.lingala.zip4j.model.ZipParameters;
import net.lingala.zip4j.util.Zip4jConstants;
import org.apache.commons.io.FileDeleteStrategy;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.w3c.tidy.Tidy;

import javax.servlet.http.HttpServletResponse;
import java.io.*;
import java.lang.reflect.Constructor;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Optional;
import java.util.Scanner;
import java.util.concurrent.atomic.AtomicInteger;

import static spark.Spark.*;

public class Main {
    //Used to separate output folders generated by different requests.
    private static AtomicInteger outputPrefixNumber = new AtomicInteger(0);

    public static void main(String[] args) {
        try {
            registerThemes();
            Config config = new Config();
            new JCommander(config, args);
            if (Files.exists(Paths.get("data"))) {
                FileDeleteStrategy.FORCE.delete(new File("data"));
            }
            setupLogging();
            Files.createDirectory(Paths.get("data"));
            //createExample();

            if (Config.serverMode) {
                if (Config.sslMode) {
                    String keystoreLocation = Optional.ofNullable(System.getenv("jresume_keystore_location")).orElseThrow(
                            () -> new InvalidEnvironmentVariableException("jresume_keystore_location is not set in the environment"));

                    String keystorePassword = Optional.ofNullable(System.getenv("jresume_keystore_password")).orElseThrow(
                            () -> new InvalidEnvironmentVariableException("jresume_keystore_password is not set in the environment"));
                    File keystore = new File(keystoreLocation);
                    System.out.println("Keystore location:" + keystore.getAbsolutePath());
                    System.out.println("Keystore exists: " + keystore.exists());
                    System.out.println("Keystore can be read: " + keystore.canRead());
                    System.out.println("Keystore can write: " + keystore.canWrite());
                    System.out.println("Keystore can execute: " + keystore.canExecute());
                    secure(keystoreLocation, keystorePassword, null, null);
                }
                startListeningAsServer();
            } else {
                generateWebResumeAndWriteIt(null, new Runtime(Config.getOutputDirectory(), outputPrefixNumber.incrementAndGet()));
            }
        } catch (Exception exc) {
            exc.printStackTrace();
        }

        //createExample();
    }

    private static void setupLogging() throws FileNotFoundException {
        if (Config.logFile != null) {
            PrintStream printStream = new PrintStream(new File(Config.logFile));
            System.setErr(printStream);
            System.setOut(printStream);
        }
    }

    private static File generateWebResumeAndWriteIt(String json, Runtime runtime) throws Exception {
        if (json == null) {
            json = readJSONFromFile();
        }
        String html = generateWebResumeFromJSON(json, runtime);
        File location = runtime.getOutputHtmlFile();
        FileWriter writer = new FileWriter(location, false);
        writer.write(html);
        //System.out.println(html);

        System.out.println("Success! You can find your resume at " + runtime.getOutputHtmlFile().getAbsolutePath());
        writer.close();
        return location.getParentFile();
    }

    public static void registerThemes() {
        BaseTheme.registerTheme("default", DefaultTheme.class);
        BaseTheme.registerTheme("blankexampletheme", BasicExampleTheme.class);
    }

    public static void createExample(){
        Person person = new Person("John Doe", "Junior Software Engineer",
                "800 Java Road, OOP City", "+1(345)-335-8964", "johndoe@gmail.com",
                "http://johndoe.com");
        JobWork jobWork = new JobWork("Example Ltd.", "Software Engineer",
                "At Example Ltd., I did such and such.");

        jobWork.addHighlight("Worked on such and such");
        jobWork.addHighlight("Also worked on this");
        jobWork.addKeyWord("java");
        jobWork.addKeyWord("c++");
        jobWork.addKeyWord("c++");

        VolunteerWork volunteerWork = new VolunteerWork("Example Institution", "Volunteer",
                "At Example Institution, I did such and such.");
        Resume resume = new Resume();
        resume.setPerson(person);
        resume.addJobWork(jobWork);
        resume.addVolunteerWork(volunteerWork);
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        String json = gson.toJson(resume);
        System.out.println(json);
        try(FileWriter writer = new FileWriter("example.json")){
            writer.write(json);
        }
        catch (Exception exc){
            exc.printStackTrace();
            stop();
        }
    }

    private static void enableCORS(final String origin, final String methods, final String headers) {

        options("/*", (request, response) -> {

            String accessControlRequestHeaders = request.headers("Access-Control-Request-Headers");
            if (accessControlRequestHeaders != null) {
                response.header("Access-Control-Allow-Headers", accessControlRequestHeaders);
            }

            String accessControlRequestMethod = request.headers("Access-Control-Request-Method");
            if (accessControlRequestMethod != null) {
                response.header("Access-Control-Allow-Methods", accessControlRequestMethod);
            }

            return "OK";
        });

        before((request, response) -> {
            response.header("Access-Control-Allow-Origin", origin);
            response.header("Access-Control-Request-Method", methods);
            response.header("Access-Control-Allow-Headers", headers);
            // Note: this may or may not be necessary in your particular application
            response.type("application/json");
        });
    }

    private static void copyResourcesZip(Runtime runtime, File destination) throws Exception {
        String classUrl = Main.class.getResource("Main.class").toString();

        URL url = Main.class.getResource("/resources.zip");
        //System.out.println("JAR Resource Zip URL: " + url.toString());
        InputStream inputStream = url.openStream();

        if (destination == null) {
            File tempFile = new File("data/jresume-data-zip-" + runtime.getId());

            if (tempFile.exists()) {
                FileDeleteStrategy.FORCE.delete(tempFile);
            }
            Files.copy(inputStream, tempFile.toPath());
            runtime.unzipResourceZip(tempFile.getAbsolutePath());
            FileDeleteStrategy.FORCE.delete(tempFile);
        } else {
            if (!destination.exists()) {
                Files.copy(inputStream, destination.toPath());
            }

        }


    }

    private static void startListeningAsServer() throws Exception {
        copyResourcesZip(new Runtime(new File("."), -1), Config.serverInitialResourceZip);
        threadPool(Config.getMaxThreads());
        port(Config.getServerPort());
        enableCORS("*", "POST, GET, OPTIONS, DELETE, PUT", "*");

        post("/webresume", (request, response) -> {
            int currentReqId = outputPrefixNumber.incrementAndGet();
            File outputDirectory = new File("data/jresume" + currentReqId + ".tmp");
            Runtime runtime = new Runtime(outputDirectory, currentReqId);
            File location = generateWebResumeAndWriteIt(request.body(), runtime);
            File outputZipFile = new File("data/jresume -" + runtime.getId() + ".tmp");
            if (outputZipFile.exists()) {
                outputZipFile.delete();
            }
            outputZipFile.deleteOnExit();

            Files.copy(Config.serverInitialResourceZip.toPath(), outputZipFile.toPath());
            ZipFile zipFile = new ZipFile(outputZipFile);
            ZipParameters parameters = new ZipParameters();
            parameters.setCompressionLevel(Zip4jConstants.DEFLATE_LEVEL_ULTRA);
            zipFile.addFile(runtime.getOutputHtmlFile(), parameters);
            HttpServletResponse rawResponse = response.raw();
            rawResponse.setContentType("application/octet-stream");
            rawResponse.setHeader("Content-Disposition", "attachment; filename=resume.zip");
            OutputStream out = rawResponse.getOutputStream();
            writeFiletoOutputStreamByteByByte(outputZipFile, out);
            FileDeleteStrategy.FORCE.delete(outputZipFile);
            FileDeleteStrategy.FORCE.delete(outputDirectory);
            return rawResponse;
        });
        get("/", (request, response) -> {
            return "Welcome to JResume!";
        });

        get("/themes", (request, response) -> {
            HashMap<String, BaseTheme> themeHashMap = Config.getThemeHashMap();
            response.type("application/json");
            JSONObject responseObj = new JSONObject();
            JSONArray themeArr = new JSONArray();
            for (String themeName : themeHashMap.keySet()) {
                themeArr.put(themeName);
            }
            responseObj.put("themes", themeArr);
            return responseObj.toString();
        });
        exception(Exception.class, (exception, request, response) -> {
            exception.printStackTrace();
        });
    }

    private static String generateWebResumeFromJSON(String json, Runtime runtime) throws Exception {
        if (!isValidJSON(json)) {
            throw new InvalidJSONException();
        }
        Gson gson = new GsonBuilder().setPrettyPrinting().create();


        copyResourcesZip(runtime, null);
        if (!Files.exists(Paths.get("output"))) {
            Files.createDirectory(Paths.get("output"));
        }


        //System.out.println(json);
        Resume resume = gson.fromJson(json, Resume.class);

        JsonParser parser = new JsonParser();
        JsonObject obj = parser.parse(json).getAsJsonObject();
        resume.setJsonObject(obj);

        BaseTheme theme;
        if (resume.getThemeName() != null) {
            theme = Config.getThemeHashMap().get(resume.getThemeName());
        } else {
            theme = Config.getThemeHashMap().get(Config.getThemeName());
        }
        if (theme == null) {
            throw new InvalidThemeNameException();
        }
        //Duplicates the theme found so that all requests will use their own instance of each theme.
        Class themeClass = theme.getClass();
        Constructor themeConstructor = themeClass.getConstructor(String.class);
        theme = (BaseTheme) themeConstructor.newInstance(theme.getThemeName());

        String html = theme.generate(resume);

        html = prettyPrintHTML(html);
        return html;
    }

    private static String prettyPrintHTML(String html) {
        String prettyHTML;
        Tidy tidy = new Tidy();
        tidy.setIndentContent(true);
        tidy.setShowWarnings(false);
        tidy.setQuiet(true);
        tidy.setTrimEmptyElements(false);

        StringReader htmlStringReader = new StringReader(html);
        StringWriter htmlStringWriter = new StringWriter();
        tidy.parseDOM(htmlStringReader, htmlStringWriter);
        prettyHTML = htmlStringWriter.toString();
        return prettyHTML;
    }

    private static String readJSONFromFile() throws Exception {
        String jsonResumePath = Config.getInputFileName();
        String json = "";
        Scanner reader = new Scanner(new File(jsonResumePath));

        while (reader.hasNextLine()) {
            json += reader.nextLine();
            json += "\n";
        }
        reader.close();
        return json;
    }

    private static void writeFiletoOutputStreamByteByByte(File file, OutputStream out) throws IOException{
        FileInputStream input = new FileInputStream(file);
        int c;
        while((c = input.read()) != -1){
            out.write(c);
        }
        input.close();
        out.close();
    }

    private static boolean isValidJSON(String json) {
        try {
            JSONObject obj = new JSONObject(json);
            return true;
        } catch (JSONException exc) {
            exc.printStackTrace();
            System.out.println("Invalid JSON:" + json);
            return false;
        }
    }
}

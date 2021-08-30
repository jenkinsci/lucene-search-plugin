package org.jenkinsci.plugins.lucene.search.bashrunner;

import org.apache.log4j.Logger;

import java.io.*;

public class BashRunner implements Runnable {
    private final BashCommands commands;
    private volatile boolean stop;
    private File script;
    private final static String scriptName = "script.sh";
    private final Logger logger = Logger.getLogger(BashRunner.class);

    public BashRunner(BashCommands commands) {
        this.commands = commands;
    }

    // run repeatedly then sleep for ... until stop() is called or interrupted
    public void run() {
        stop = false;
        script = new File(scriptName);
        try {
            FileWriter fileWriter = new FileWriter(scriptName);
            fileWriter.write(commands.getCommands());
            fileWriter.close();
            while (!stop) {
                runOnce();
                Thread.sleep(5000);
            }
        } catch (IOException e) {
            System.out.printf("BashRunner is down: \n %s \n", e);
        } catch (InterruptedException e) {
            System.out.printf("Thread error in BashRunner: \n %s \n", e);
        } finally {
            script.delete();
        }
    }

    private void runOnce() throws IOException {
        Process process = Runtime.getRuntime().exec(String.format("bash %s", scriptName));
        BufferedReader br = new BufferedReader(new InputStreamReader(process.getInputStream()));
        String line;
        while ((line = br.readLine()) != null) {
            logger.debug(line);
            System.out.println(line);
        }
    }

    public void stop() {
        stop = true;
    }

    public static void main(String[] args) throws InterruptedException {
        BashRunner br = new BashRunner(BashCommands.rm_cache);
        Thread t = new Thread(br);
        t.start();
        System.out.println("Started");
        Thread.sleep(8000);
        br.stop();
        System.out.println("Stopped");
        t.join();
        System.out.println("Joined");
    }

    public enum BashCommands {
        rm_cache("if [ $(free --mega | grep Mem | awk '{print $6}') -gt 10 ];" +
                "then echo \"Cleaning cache now...\";" +
                "sync && sudo sh -c 'echo 3 > /proc/sys/vm/drop_caches';" +
                "echo \"Cleaning cache finished...\" ;" +
                "fi"),
        hello_world("echo HelloWorld");
        private final String commands;

        BashCommands(String commands) {
            this.commands = commands;
        }

        public String getCommands() {
            return commands;
        }
    }
}


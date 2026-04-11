
import java.io.*;
import java.time.*;

public class Logger {

    public static synchronized void log(String city, String msg, String user){
        try(PrintWriter pw = new PrintWriter(new FileWriter(city + user + ".txt", true))){
            pw.println(LocalDateTime.now() + " : " + msg);
        }
        catch(Exception e) {
        	
        }
    }
}

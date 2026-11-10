import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.springframework.security.crypto.bcrypt.BCrypt;

class SeedPasswordHash {
    public static void main(String[] args) throws Exception {
        var input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        System.out.println(BCrypt.hashpw(input.readLine(), BCrypt.gensalt(10)));
    }
}

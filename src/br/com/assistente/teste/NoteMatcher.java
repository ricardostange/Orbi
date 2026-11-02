package br.com.assistente.teste;
import java.util.List;
public final class NoteMatcher {
    private NoteMatcher() {}
    /** Exact, case-sensitive match; duplicate entries are removed one at a time. */
    public static int firstExact(List<String> notes, String target) {
        if(target==null || target.isEmpty()) return -1;
        for(int i=0;i<notes.size();i++)if(target.equals(notes.get(i)))return i;
        return -1;
    }
}

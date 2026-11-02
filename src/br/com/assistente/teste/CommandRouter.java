package br.com.assistente.teste;
import java.text.Normalizer;
import java.util.Locale;
/** Deterministic tool dispatcher. Replace routing with an LLM in a later version. */
public final class CommandRouter {
    public static final class Command {
        public final String tool, argument;
        Command(String tool, String argument) { this.tool=tool; this.argument=argument; }
    }
    public static Command parse(String input) {
        String raw=input==null ? "" : input.trim();
        String n=Normalizer.normalize(raw,Normalizer.Form.NFD).replaceAll("\\p{M}","").toLowerCase(Locale.ROOT).replaceAll("[?!.,]+$","").trim();
        if(n.matches("(ola|oi|hello world|ola mundo|teste)")) return new Command("hello","");
        if(n.matches("(que horas sao|que hora e|qual e a hora|horas|hora)")) return new Command("time","");
        if(n.matches("(ler notas|leia as notas|minhas notas|mostrar notas|listar notas)")) return new Command("list_notes","");
        if(n.matches("(ajuda|comandos|o que voce faz)")) return new Command("help","");
        if(raw.toLowerCase(Locale.ROOT).startsWith("remover ")) {
            String body=raw.substring(8).trim();
            if(!body.isEmpty()) return new Command("remove_note",body);
        }
        // Accent normalization preserves offsets for Portuguese characters.
        for(String prefix:new String[]{"anotar ","anote ","nota "}) if(n.startsWith(prefix)) {
            String body=raw.substring(prefix.length()).trim();
            if(!body.isEmpty()) return new Command("save_note",body);
        }
        return new Command("unknown","");
    }
}

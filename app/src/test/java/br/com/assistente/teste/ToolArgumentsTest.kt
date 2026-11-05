package br.com.assistente.teste

import br.com.assistente.teste.integrations.ToolArguments
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ToolArgumentsTest {
    private val schema = JSONObject("""{
        "type":"object","required":["name","enabled","count"],"additionalProperties":false,
        "properties":{
            "name":{"type":"string","enum":["a","b"]},
            "enabled":{"type":"boolean"},
            "count":{"type":"integer","minimum":0,"maximum":5},
            "ids":{"type":"array","items":{"type":"string"},"maxItems":2}
        }
    }""")
    @Test fun `accepts false zero and typed arrays`() {
        ToolArguments.validate(JSONObject("""{"name":"a","enabled":false,"count":0,"ids":["x"]}"""), schema)
    }
    @Test fun `rejects invalid inputs without coercion`() {
        val invalid = listOf(
            """{"name":"a","enabled":"false","count":0}""",
            """{"name":"c","enabled":true,"count":0}""",
            """{"name":"a","enabled":true,"count":1.5}""",
            """{"name":"a","enabled":true,"count":6}""",
            """{"name":"a","enabled":true,"count":-1}""",
            """{"name":"a","enabled":null,"count":0}""",
            """{"name":"a","count":0}""",
            """{"name":"a","enabled":true,"count":0,"extra":1}""",
            """{"name":"a","enabled":true,"count":0,"ids":[1]}""",
            """{"name":"a","enabled":true,"count":0,"ids":["a","b","c"]}"""
        )
        invalid.forEach { input ->
            assertThrows(input, IllegalArgumentException::class.java) { ToolArguments.validate(JSONObject(input), schema) }
        }
    }
}

package com.lanqi.weiassist.agent

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuestionsTest {
    private val request = JSONObject("""
        {"type":"ask_user","session":"s1","ask":"q3","questions":[
          {"id":"city","question":"Which city?","header":"City","options":[{"label":"Hangzhou","description":"Where you are"},{"label":"Beijing"}],"multiSelect":false},
          {"id":"unit","question":"Which units?","options":[{"label":"°C"},{"label":"°F"}],"multiSelect":true},
          {"id":"plan","question":"Approve the plan?","detail":"1. Do it","options":[{"label":"Approve"},{"label":"Keep planning"}],
           "intent":{"kind":"plan-review","approve":"Approve"}}
        ]}
    """)

    private val ask = Ask.parse(request)!!

    @Test fun `the host call's questions are read`() {
        assertEquals("q3", ask.id)
        assertEquals(3, ask.questions.size)
        val city = ask.questions[0]
        assertEquals("Which city?", city.text)
        assertEquals("City", city.header)
        assertEquals(listOf(QuestionOption("Hangzhou", "Where you are"), QuestionOption("Beijing")), city.options)
        assertFalse(city.multi)
        assertTrue(ask.questions[1].multi)
        assertEquals(QuestionIntent("plan-review", "Approve"), ask.questions[2].intent)
        assertEquals("1. Do it", ask.questions[2].detail)
    }

    @Test fun `no questions is no ask`() {
        assertNull(Ask.parse(JSONObject().put("ask", "q1").put("questions", JSONArray())))
        assertNull(Ask.parse(JSONObject().put("ask", "q1")))
    }

    @Test fun `a single choice answers at once, several are picked then confirmed, typed words answer too`() {
        var d = AnswerDraft(ask)
        d = d.choose("Beijing")
        assertEquals(1, d.index)
        d = d.choose("°C").choose("°F").choose("°C") // toggled back off
        assertEquals(listOf("°F"), d.picked)
        d = d.confirm()
        assertFalse(d.done)
        d = d.custom("  Go ahead, but skip step 2  ")
        assertTrue(d.done)
        assertEquals(
            listOf(QuestionAnswer("city", listOf("Beijing")), QuestionAnswer("unit", listOf("°F")), QuestionAnswer("plan", emptyList(), "Go ahead, but skip step 2")),
            d.answers,
        )
    }

    @Test fun `typed words on a multiple choice keep what is picked, and a skip is an empty answer`() {
        var d = AnswerDraft(ask).skip()
        assertTrue(d.answers[0].skipped)
        d = d.choose("°C").custom("and Kelvin")
        assertEquals(QuestionAnswer("unit", listOf("°C"), "and Kelvin"), d.answers[1])
        // Blank words don't answer.
        assertEquals(d, d.custom("   "))
    }

    @Test fun `the reply has every answer, or why there is none`() {
        val r = Ask.reply(listOf(QuestionAnswer("city", listOf("Beijing")), QuestionAnswer("unit")), "unused")
        val a = r.getJSONArray("answers")
        assertEquals("""{"id":"city","selected":["Beijing"]}""", a.getJSONObject(0).toString())
        assertEquals("""{"id":"unit","selected":[]}""", a.getJSONObject(1).toString())
        assertEquals("Stopped.", Ask.reply(null, "Stopped.").getString("reason"))
        assertFalse(Ask.reply(null, "Stopped.").has("answers"))
    }
}

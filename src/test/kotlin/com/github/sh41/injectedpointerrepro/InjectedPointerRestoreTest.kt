package com.github.sh41.injectedpointerrepro

import com.intellij.lang.Language
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.lang.injection.MultiHostInjector
import com.intellij.lang.injection.MultiHostRegistrar
import com.intellij.lang.xml.XMLLanguage
import com.intellij.openapi.fileTypes.PlainTextLanguage
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiLanguageInjectionHost
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlText
import com.intellij.testFramework.LoggedErrorProcessor
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Injects each of the given languages over the whole text of an XML text node, then creates a smart pointer to the first
 * leaf of each injected file. In unit-test mode
 * [`SmartPsiElementPointerImpl.createElementInfo`](https://github.com/JetBrains/intellij-community/blob/b1b4aca3b97cb0a770e3e8fc055dccdd6b1d6f92/platform/core-impl/src/com/intellij/psi/impl/smartPointers/SmartPsiElementPointerImpl.java#L154-L172)
 * restores every new pointer at once and logs "Cannot restore ..." when the result differs, so creation alone exercises
 * [`InjectedSelfElementInfo.getInjectedFileIn`](https://github.com/JetBrains/intellij-community/blob/b1b4aca3b97cb0a770e3e8fc055dccdd6b1d6f92/platform/core-impl/src/com/intellij/psi/impl/smartPointers/InjectedSelfElementInfo.java#L109-L145).
 */
class InjectedPointerRestoreTest : BasePlatformTestCase() {

    private class InjectEachOverWholeHost(private val languages: List<Language>) : MultiHostInjector {
        override fun getLanguagesToInject(registrar: MultiHostRegistrar, context: PsiElement) {
            val host = context as? XmlText ?: return
            val range = TextRange(0, host.textLength)
            if (range.isEmpty) return
            for (language in languages) {
                registrar.startInjecting(language)
                registrar.addPlace(null, null, host as PsiLanguageInjectionHost, range)
                registrar.doneInjecting()
            }
        }

        override fun elementsToInjectIn(): List<Class<out PsiElement>> = listOf(XmlText::class.java)
    }

    private data class Outcome(val injectedLanguage: String, val restoredLanguage: String?, val loggedError: String?) {
        val restored: Boolean get() = restoredLanguage == injectedLanguage && loggedError == null

        override fun toString(): String =
            if (restored) "$injectedLanguage: restored"
            else "$injectedLanguage: restored as $restoredLanguage${loggedError?.let { ", logged \"$it\"" } ?: ""}"
    }

    private fun register(vararg languages: Language) {
        InjectedLanguageManager.getInstance(project)
            .registerMultiHostInjector(InjectEachOverWholeHost(languages.toList()), testRootDisposable)
    }

    /** Configures a fresh host file, then reports per injected language whether its pointer restores. */
    private fun outcomes(fileName: String, vararg expected: Language): List<Outcome> {
        myFixture.configureByText(fileName, "<root>hello world</root>")
        val host = PsiTreeUtil.findChildOfType(myFixture.file, XmlText::class.java)!!
        val injectedFiles: List<PsiFile> =
            InjectedLanguageManager.getInstance(project).getInjectedPsiFiles(host).orEmpty()
                .map { it.first.containingFile }
                .distinctBy { it.language }
        assertEquals(
            "the injector must inject every language, or the outcome below means nothing",
            expected.map { it.id }.toSet(),
            injectedFiles.map { it.language.id }.toSet()
        )

        val manager = SmartPointerManager.getInstance(project)
        return injectedFiles.map { file ->
            val logged = mutableListOf<String>()
            val processor = object : LoggedErrorProcessor() {
                override fun processError(category: String, message: String, details: Array<out String>, t: Throwable?): Set<Action> {
                    logged.add(message.substringBefore(" in Project"))
                    return Action.NONE
                }
            }
            var restoredLanguage: String? = null
            LoggedErrorProcessor.executeWith<RuntimeException>(processor) {
                // A leaf, not the file: only an ASTNode or ASTDelegatePsiElement gets an InjectedSelfElementInfo, per
                // https://github.com/JetBrains/intellij-community/blob/b1b4aca3b97cb0a770e3e8fc055dccdd6b1d6f92/platform/core-impl/src/com/intellij/psi/impl/smartPointers/SmartPsiElementPointerImpl.java#L250-L252
                val pointer = manager.createSmartPsiElementPointer(PsiTreeUtil.getDeepestFirst(file))
                restoredLanguage = pointer.element?.containingFile?.language?.id
            }
            Outcome(file.language.id, restoredLanguage, logged.firstOrNull())
        }
    }

    fun testPointerIntoTheOnlyInjectionRestores() {
        register(XMLLanguage.INSTANCE)

        val outcomes = outcomes("host.xml", XMLLanguage.INSTANCE)

        assertTrue(
            "the pointer into the only injected file should restore, but got:\n  ${outcomes.joinToString("\n  ")}",
            outcomes.all { it.restored }
        )
    }

    fun testPointersIntoTwoInjectionsOverTheSameHostRangeEachRestoreIntoTheirOwnLanguage() {
        register(XMLLanguage.INSTANCE, PlainTextLanguage.INSTANCE)

        val outcomes = outcomes("host.xml", XMLLanguage.INSTANCE, PlainTextLanguage.INSTANCE)

        assertTrue(
            "with XML and plain text injected over the same host range, each pointer should restore into its own " +
                "injected file, but got:\n  ${outcomes.joinToString("\n  ")}",
            outcomes.all { it.restored }
        )
    }

    /** The same scenario many times over in one JVM: which injection loses varies with each new file's hash order. */
    fun testWhichPointerFailsVariesBetweenIdenticalRuns() {
        register(XMLLanguage.INSTANCE, PlainTextLanguage.INSTANCE)
        val iterations = 1000

        val failuresPerLanguage = mutableMapOf<String, Int>()
        repeat(iterations) { i ->
            for (outcome in outcomes("host$i.xml", XMLLanguage.INSTANCE, PlainTextLanguage.INSTANCE)) {
                if (!outcome.restored) failuresPerLanguage.merge(outcome.injectedLanguage, 1, Int::plus)
            }
        }

        val summary = failuresPerLanguage.entries.sortedBy { it.key }.joinToString { "${it.key} failed ${it.value}" }
        assertTrue(
            "over $iterations runs of the same scenario, every pointer should restore into its own injected file, " +
                "but: ${summary.ifEmpty { "none failed" }}",
            failuresPerLanguage.isEmpty()
        )
    }

    /**
     * Same claim as [testWhichPointerFailsVariesBetweenIdenticalRuns], with the registration order swapped: every
     * pointer should still restore. It fails the same way, and the message reports which language now loses more
     * often — evidence that the failure follows registration order, not the language itself.
     */
    fun testWhichInjectionLosesFollowsRegistrationOrderNotLanguage() {
        register(PlainTextLanguage.INSTANCE, XMLLanguage.INSTANCE)
        val iterations = 1000

        val failuresPerLanguage = mutableMapOf<String, Int>()
        repeat(iterations) { i ->
            for (outcome in outcomes("swapped$i.xml", PlainTextLanguage.INSTANCE, XMLLanguage.INSTANCE)) {
                if (!outcome.restored) failuresPerLanguage.merge(outcome.injectedLanguage, 1, Int::plus)
            }
        }

        val summary = failuresPerLanguage.entries.sortedBy { it.key }.joinToString { "${it.key} failed ${it.value}" }
        assertTrue(
            "with plain text registered first this time (the earlier test had XML first), every pointer should " +
                "restore into its own injected file, but: ${summary.ifEmpty { "none failed" }}",
            failuresPerLanguage.isEmpty()
        )
    }
}

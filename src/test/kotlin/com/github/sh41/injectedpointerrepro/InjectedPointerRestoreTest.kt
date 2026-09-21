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
 * leaf of each injected file. In unit-test mode `SmartPsiElementPointerImpl.createElementInfo` restores every new pointer
 * at once and logs "Cannot restore ..." when the result differs, so creation alone exercises
 * `InjectedSelfElementInfo.getInjectedFileIn`.
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

    private data class Outcome(val restoredLanguage: String?, val loggedErrors: List<String>)

    /** For each injected language: the language its pointer restores to, and any error logged while creating it. */
    private fun outcomes(vararg languages: Language): Map<String, Outcome> {
        InjectedLanguageManager.getInstance(project)
            .registerMultiHostInjector(InjectEachOverWholeHost(languages.toList()), testRootDisposable)
        myFixture.configureByText("host.xml", "<root>hello world</root>")

        val host = PsiTreeUtil.findChildOfType(myFixture.file, XmlText::class.java)!!
        val injectedFiles: List<PsiFile> =
            InjectedLanguageManager.getInstance(project).getInjectedPsiFiles(host).orEmpty()
                .map { it.first.containingFile }
                .distinctBy { it.language }
        assertEquals(languages.map { it.id }.toSet(), injectedFiles.map { it.language.id }.toSet())

        val manager = SmartPointerManager.getInstance(project)
        return injectedFiles.associate { file ->
            val logged = mutableListOf<String>()
            val processor = object : LoggedErrorProcessor() {
                override fun processError(category: String, message: String, details: Array<out String>, t: Throwable?): Set<Action> {
                    logged.add(message.substringBefore(" in Project"))
                    return Action.NONE
                }
            }
            // A leaf, not the file: only an ASTNode or ASTDelegatePsiElement gets an InjectedSelfElementInfo.
            var restoredLanguage: String? = null
            LoggedErrorProcessor.executeWith<RuntimeException>(processor) {
                val pointer = manager.createSmartPsiElementPointer(PsiTreeUtil.getDeepestFirst(file))
                restoredLanguage = pointer.element?.containingFile?.language?.id
            }
            file.language.id to Outcome(restoredLanguage, logged)
        }
    }

    fun testPointerIntoTheOnlyInjectionRestores() {
        val outcomes = outcomes(XMLLanguage.INSTANCE)

        assertEquals(mapOf(XMLLanguage.INSTANCE.id to Outcome(XMLLanguage.INSTANCE.id, emptyList())), outcomes)
    }

    fun testPointersIntoTwoInjectionsOverTheSameHostRangeEachRestoreIntoTheirOwnLanguage() {
        val outcomes = outcomes(XMLLanguage.INSTANCE, PlainTextLanguage.INSTANCE)

        assertEquals(outcomes.keys.associateWith { Outcome(it, emptyList()) }, outcomes)
    }
}

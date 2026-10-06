package de.dadecker.inubit.mcp.adapter.xslt;

/** Test stylesheets of this package for tests elsewhere. */
public final class XsltRunnerTestAccess {

    private XsltRunnerTestAccess() {
    }

    /** A stylesheet that would run for ages. */
    public static String endless() {
        return XsltRunnerTest.ENDLESS;
    }
}

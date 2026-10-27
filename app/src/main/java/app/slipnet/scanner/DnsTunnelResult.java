// Derived from SlipNet (github.com/anonvector/SlipNet), AGPL-3.0.

package app.slipnet.scanner;

public final class DnsTunnelResult {
    public boolean nsSupport, txtSupport, randomSubdomain, tunnelRealism, edns0Support, nxdomainCorrect;
    public int ednsMaxPayload;                 // 0, 512, 900 or 1232

    public static final int MAX_SCORE = 6;

    private static final String OK = "\u2713";
    private static final String NO = "\u2717";

    public int score() {
        int s = 0;
        if (nsSupport) s++;
        if (txtSupport) s++;
        if (randomSubdomain) s++;
        if (tunnelRealism) s++;
        if (edns0Support) s++;
        if (nxdomainCorrect) s++;
        return s;
    }

    public boolean isCompatible() {
        return score() == MAX_SCORE;
    }

    public String details() {
        return "NS\u2192A" + (nsSupport ? OK : NO)
                + " TXT" + (txtSupport ? OK : NO)
                + " RND" + (randomSubdomain ? OK : NO)
                + " DPI" + (tunnelRealism ? OK : NO)
                + " EDNS" + (edns0Support ? OK : NO) + "(" + ednsMaxPayload + ")"
                + " NXD" + (nxdomainCorrect ? OK : NO);
    }
}

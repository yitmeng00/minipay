package com.minipay.refdata.internal.domain;

import com.minipay.common.id.Iban;
import com.minipay.refdata.api.IbanCheck;
import com.minipay.refdata.api.IbanCheck.Check;
import com.minipay.refdata.api.IbanCheck.Outcome;
import com.minipay.refdata.api.IbanCheck.Part;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

/**
 * Validates an IBAN in the order a bank's payment screen does, and reports every check:
 *
 * <ol>
 *   <li><b>Country supported:</b> the country is in the IBAN registry on that date.</li>
 *   <li><b>Character set:</b> two letters, two digits, then letters and digits only.</li>
 *   <li><b>Length:</b> exactly the country's length (DE 22, GB 22, BE 16, FR 27 …).</li>
 *   <li><b>BBAN structure:</b> each national part has the right kind of characters (a GB sort code is 6 digits).</li>
 *   <li><b>Check digits:</b> mod-97 leaves remainder 1.</li>
 *   <li><b>Bank code known:</b> the national bank code maps to a BIC. This is a <i>warning</i> only, because
 *       an IBAN can be perfectly valid at a bank our IBAN-to-BIC map does not list.</li>
 * </ol>
 * National check digits inside the BBAN (Belgium's last two digits, France's RIB key) are shown as
 * parts, but not verified.
 */
public final class IbanValidator {

    private IbanValidator() {
    }

    /**
     * @param format     the country's registry entry on the date, if any
     * @param bicForBank (country, bank code) → BIC, from the IBAN-to-BIC map on the date
     */
    public static IbanCheck validate(String input, Optional<IbanFormat> format,
                                     BiFunction<String, String, Optional<String>> bicForBank) {
        String s = Iban.normalize(input == null ? "" : input);
        String cc = s.length() >= 2 ? s.substring(0, 2) : "";
        List<Part> parts = new ArrayList<>();
        List<Check> checks = new ArrayList<>();

        if (s.length() >= 2) {
            parts.add(new Part("Country", "COUNTRY", cc));
        }
        if (s.length() >= 4) {
            parts.add(new Part("Check digits", "CHECK_DIGITS", s.substring(2, 4)));
        }
        String bankId = null;
        if (format.isPresent()) {
            int pos = 4;
            for (IbanFormat.BbanPart p : format.get().parts()) {
                if (pos >= s.length()) {
                    break;
                }
                String v = s.substring(pos, Math.min(s.length(), pos + p.length()));
                parts.add(new Part(p.name(), p.role(), v));
                if (p.role().equals("BANK")) {
                    bankId = v;
                }
                pos += p.length();
            }
        } else if (s.length() > 4) {
            parts.add(new Part("BBAN", "BBAN", s.substring(4)));
        }

        // 1. Country
        checks.add(format.isPresent()
                ? new Check("Country supported", Outcome.PASS, cc + " · length " + format.get().length())
                : new Check("Country supported", Outcome.FAIL,
                        cc.isEmpty() ? "No country code" : cc + " is not in the IBAN registry (the country does not use IBANs, or is not configured)"));

        // 2. Characters
        boolean charsOk = Iban.isWellFormed(s);
        checks.add(new Check("Character set", charsOk ? Outcome.PASS : Outcome.FAIL,
                charsOk ? "A–Z, 0–9 only" : "2 letters, then 2 digits, then letters or digits (at most 34)"));

        // 3. Length
        boolean lengthOk = format.isPresent() && s.length() == format.get().length();
        checks.add(new Check("Length", lengthOk ? Outcome.PASS : Outcome.FAIL,
                format.map(f -> s.length() + " of " + f.length() + " characters").orElse(s.length() + " characters")));

        // 4. BBAN structure
        if (format.isPresent() && lengthOk && charsOk) {
            String wrong = null;
            int pos = 4;
            for (IbanFormat.BbanPart p : format.get().parts()) {
                if (!p.accepts(s.substring(pos, pos + p.length()))) {
                    wrong = p.name() + " must be " + p.describe();
                    break;
                }
                pos += p.length();
            }
            String notation = format.get().parts().stream().map(IbanFormat.BbanPart::notation).collect(Collectors.joining(" "));
            checks.add(new Check("BBAN structure", wrong == null ? Outcome.PASS : Outcome.FAIL, wrong == null ? notation : wrong));
        } else {
            checks.add(new Check("BBAN structure", Outcome.FAIL, "needs a full-length IBAN of a supported country"));
        }

        // 5. Check digits
        boolean modOk = false;
        if (charsOk && lengthOk) {
            int r = Iban.remainder(s);
            modOk = r == 1;
            checks.add(new Check("Check digits (mod-97)", modOk ? Outcome.PASS : Outcome.FAIL, modOk
                    ? "remainder 1"
                    : "remainder " + r + " · expected check digits " + Iban.expectedCheckDigits(cc, s.substring(4))));
        } else {
            checks.add(new Check("Check digits (mod-97)", Outcome.FAIL, "needs a full-length IBAN"));
        }

        // 6. Bank code → BIC
        Optional<String> bic = bankId == null ? Optional.empty() : bicForBank.apply(cc, bankId);
        String bankIdShown = bankId == null ? "—" : bankId;
        checks.add(bic.isPresent()
                ? new Check("Bank code in directory", Outcome.PASS, bankIdShown + " → " + bic.get())
                : new Check("Bank code in directory", Outcome.WARN, bankIdShown + " has no BIC in the IBAN-to-BIC map"));

        boolean valid = format.isPresent() && charsOk && lengthOk && modOk
                && checks.stream().noneMatch(c -> c.outcome() == Outcome.FAIL);
        return new IbanCheck(input, s, Iban.printFormat(s), valid, cc, parts, checks, bankId, bic.orElse(null));
    }
}

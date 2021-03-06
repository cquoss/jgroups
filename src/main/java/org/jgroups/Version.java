


package org.jgroups;

/**
 * We're using the scheme described at http://www.jboss.com/index.html?module=bb&op=viewtopic&t=77231
 * for major, minor and micro version numbers. We have 5 bits for major and minor version numbers each and
 * 6 bits for the micro version.
 * This gives:
 * X = 0-31 for major versions
 * Y = 0-31 for minor versions
 * Z = 0-63 for micro versions
 *
 * @author Bela Ban
 * @version $Id: Version.java,v 1.42.2.6 2007/07/31 07:17:55 belaban Exp $
 * Holds version information for JGroups.
 */
public class Version {
    public static final short major = 2;
    public static final short minor = 4;
    public static final short micro = 1;
    public static final String description="2.4.1 SP-4";

    public static final short version=encode(major, minor, micro);
    public static final String string_version=print(version);
    public static final String cvs="$Id: Version.java,v 1.42.2.6 2007/07/31 07:17:55 belaban Exp $";

    private static final int MAJOR_SHIFT = 11;
    private static final int MINOR_SHIFT = 6;
    private static final int MAJOR_MASK  = 0x00f800; // 1111100000000000 bit mask
    private static final int MINOR_MASK  = 0x0007c0; //      11111000000 bit mask
    private static final int MICRO_MASK  = 0x00003f; //           111111 bit mask



    /**
     * Prints the value of the description and cvs fields to System.out.
     * @param args
     */
    public static void main(String[] args) {
        System.out.println("\nVersion: \t" + description);
        System.out.println("CVS: \t\t" + cvs);
        System.out.println("History: \t(see doc/history.txt for details)\n");
    }


    /**
     * Returns the catenation of the description and cvs fields.
     * @return String with description
     */
    public static String printDescription() {
        return "JGroups " + description + " [" + cvs + "]";
    }

    /**
     * Returns the version field as a String.
     * @return String with version
     */
    public static String printVersion() {
        return string_version;
    }


    /**
     * Compares the specified version number against the current version number.
     * @param v short
     * @return Result of == operator.
     */
    public static boolean isSame(short v) {
        return version == v;
    }

    /** Method copied from http://www.jboss.com/index.html?module=bb&op=viewtopic&t=77231 */
    public static short encode(int major, int minor, int micro) {
        return (short)((major << MAJOR_SHIFT) + (minor << MINOR_SHIFT) + micro);
}

    /** Method copied from http://www.jboss.com/index.html?module=bb&op=viewtopic&t=77231 */
    public static String print(short version) {
        int major=(version & MAJOR_MASK) >> MAJOR_SHIFT;
        int minor=(version & MINOR_MASK) >> MINOR_SHIFT;
        int micro=(version & MICRO_MASK);
        return major + "." + minor + "." + micro;
    }


    public static short[] decode(short version) {
        short major=(short)((version & MAJOR_MASK) >> MAJOR_SHIFT);
        short minor=(short)((version & MINOR_MASK) >> MINOR_SHIFT);
        short micro=(short)(version & MICRO_MASK);
        return new short[]{major, minor, micro};
    }

    /**
     * Checks whether ver is binary compatible with the current version. The rule for binary compatibility is that
     * the major and minor versions have to match, whereas micro versions can differ.
     * @param ver
     * @return
     */
    public static boolean isBinaryCompatible(short ver) {
        if(ver == version)
            return true;
        short[] tmp=decode(ver);
        short tmp_major=tmp[0], tmp_minor=tmp[1];
        return major == tmp_major && minor == tmp_minor;
    }


    public static boolean isBinaryCompatible(short ver1, short ver2) {
        if(ver1 == ver2)
            return true;
        short[] tmp=decode(ver1);
        short tmp_major=tmp[0], tmp_minor=tmp[1];
        tmp=decode(ver2);
        short tmp_major2=tmp[0], tmp_minor2=tmp[1];
        return tmp_major == tmp_major2 && tmp_minor == tmp_minor2;
    }

}

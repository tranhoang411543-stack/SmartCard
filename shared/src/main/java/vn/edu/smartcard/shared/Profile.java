package vn.edu.smartcard.shared;

import java.text.Normalizer;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.Base64;
import java.util.Locale;

public record Profile(
    String cardId,
    String studentCode,
    String fullName,
    String citizenId,
    String birthDate,
    String className,
    String phone,
    String email,
    int intakeYear,
    String photoBase64,
    long version) {
  public static final int MAX_PHOTO = 24576;

  public Profile normalized() {
    String code = text(studentCode).toUpperCase(Locale.ROOT);
    if (!code.matches("(AT|CT|DT)[0-9]{6}"))
      throw new IllegalArgumentException("Mã sinh viên phải gồm AT/CT/DT và 6 chữ số.");
    int cohort = Integer.parseInt(code.substring(2, 4));
    int group = Integer.parseInt(code.substring(4, 6));
    int ordinal = Integer.parseInt(code.substring(6, 8));
    if (cohort < 1 || group < 1 || group > 26 || ordinal < 1)
      throw new IllegalArgumentException("Khóa, lớp hoặc số thứ tự sinh viên không hợp lệ.");
    String derivedClass = code.substring(0, 2) + cohort + (char) ('A' + group - 1);
    if (!text(className).isEmpty() && !text(className).equalsIgnoreCase(derivedClass))
      throw new IllegalArgumentException(
          "Lớp không khớp mã sinh viên; lớp đúng là " + derivedClass + ".");
    String name = titleCase(fullName);
    if (name.length() < 3 || name.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 160)
      throw new IllegalArgumentException(
          "Họ tên phải có từ 3 ký tự và không vượt quá 160 byte UTF-8.");
    if (!text(citizenId).matches("[0-9]{12}"))
      throw new IllegalArgumentException("CCCD phải có đúng 12 chữ số.");
    LocalDate dob;
    try {
      dob =
          LocalDate.parse(
              text(birthDate),
              DateTimeFormatter.ofPattern("dd/MM/uuuu").withResolverStyle(ResolverStyle.STRICT));
    } catch (RuntimeException ex) {
      throw new IllegalArgumentException("Ngày sinh phải hợp lệ và có dạng dd/MM/yyyy.");
    }
    if (dob.isAfter(LocalDate.now()) || dob.isBefore(LocalDate.of(1900, 1, 1)))
      throw new IllegalArgumentException("Ngày sinh không hợp lệ.");
    if (!text(phone).matches("0[0-9]{9}"))
      throw new IllegalArgumentException("Số điện thoại phải có 10 chữ số, bắt đầu bằng 0.");
    String mail = text(email);
    if (mail.length() > 150 || !mail.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+"))
      throw new IllegalArgumentException("Email không hợp lệ.");
    if (intakeYear < 1990 || intakeYear > LocalDate.now().getYear() + 1)
      throw new IllegalArgumentException("Năm nhập học không hợp lệ.");
    byte[] photo;
    try {
      photo = Base64.getDecoder().decode(text(photoBase64));
    } catch (IllegalArgumentException ex) {
      throw new IllegalArgumentException("Ảnh không hợp lệ.");
    }
    if (photo.length == 0 || photo.length > MAX_PHOTO)
      throw new IllegalArgumentException("Ảnh thẻ phải có dữ liệu và tối đa 24 KB sau khi nén.");
    if (!text(cardId).isEmpty() && !text(cardId).matches("TTM[0-9]{6}"))
      throw new IllegalArgumentException("ID thẻ không hợp lệ.");
    return new Profile(
        text(cardId),
        code,
        name,
        text(citizenId),
        text(birthDate),
        derivedClass,
        text(phone),
        mail,
        intakeYear,
        text(photoBase64),
        version);
  }

  public Profile withCard(String id, long revision) {
    return new Profile(
        id,
        studentCode,
        fullName,
        citizenId,
        birthDate,
        className,
        phone,
        email,
        intakeYear,
        photoBase64,
        revision);
  }

  public Profile withoutPhoto() {
    return new Profile(
        cardId,
        studentCode,
        fullName,
        citizenId,
        birthDate,
        className,
        phone,
        email,
        intakeYear,
        "",
        version);
  }

  public int birthYear() {
    return Integer.parseInt(birthDate.substring(6));
  }

  public String program() {
    return studentCode.substring(0, 2);
  }

  public int cohort() {
    return Integer.parseInt(studentCode.substring(2, 4));
  }

  public static String text(String value) {
    return value == null ? "" : value.strip();
  }

  public static String titleCase(String value) {
    StringBuilder result = new StringBuilder();
    for (String word :
        text(value)
            .replaceAll("\\s+", " ")
            .toLowerCase(Locale.forLanguageTag("vi-VN"))
            .split(" ")) {
      if (word.isEmpty()) continue;
      if (!result.isEmpty()) result.append(' ');
      int first = word.codePointAt(0);
      result
          .appendCodePoint(Character.toTitleCase(first))
          .append(word.substring(Character.charCount(first)));
    }
    return result.toString();
  }

  public static String searchable(String value) {
    return Normalizer.normalize(text(value).toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
        .replaceAll("\\p{M}", "")
        .replace('đ', 'd');
  }
}

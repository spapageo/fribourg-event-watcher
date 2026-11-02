package ch.fribourgevents.store;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.time.Instant;

@Converter(autoApply = true)
public class InstantTextConverter implements AttributeConverter<Instant, String> {

  @Override
  public String convertToDatabaseColumn(Instant attribute) {
    return attribute == null ? null : InstantText.format(attribute);
  }

  @Override
  public Instant convertToEntityAttribute(String dbData) {
    return dbData == null ? null : InstantText.parse(dbData);
  }
}

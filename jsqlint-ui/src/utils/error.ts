export const isFormValidationAborted = (error: unknown) => error === false

export const isDialogCanceled = (error: unknown) => error === 'cancel' || error === 'close'

export const shouldShowActionError = (error: unknown) => {
  return !isFormValidationAborted(error) && !isDialogCanceled(error)
}
